#!/usr/bin/env python3
"""Mock identity-codec adapter: the worked example of the pocketbench adapter contract, and the
fixture the pocketbench unit tests drive so the suite needs no C/Rust build.

compress/decompress copy bytes, so round-trip is exact. Imports nothing from pocketbench: an
adapter is an independent executable that only speaks the contract over argv + stdout.
"""

from __future__ import annotations

import json
import sys
import time
from pathlib import Path

# build_profile and limitations are omitted, exercising the driver's defaults for the optional fields.
CAPABILITIES = {
    "ops": ["compress", "decompress"],
    "timing_tier": "in_process",
    "reference_conformant": True,
    "conformance_compress": True,
    "conformance_decompress": True,
    "param_schedule": "identity",
}

PARAM_KEYS = ("packet-bits", "pt", "ft", "rt", "robustness")


class UsageError(Exception):
    """A malformed invocation: exits 2."""


def _flags(sub: str, argv: list[str], keys: tuple[str, ...]) -> dict[str, str]:
    """Parse `--key=value` flags. Every key is required and unknown keys are rejected."""
    seen: dict[str, str] = {}
    for arg in argv:
        if not arg.startswith("--"):
            raise UsageError(
                f"{sub}: unexpected positional argument {arg!r}; the contract passes keyed flags"
            )
        key, sep, value = arg[2:].partition("=")
        if not sep:
            raise UsageError(f"{sub}: flag {arg!r} needs a value, written --key=value")
        if key not in keys:
            raise UsageError(
                f"{sub}: unknown flag --{key}; this adapter does not implement it, so the harness "
                "and the adapter disagree about the contract"
            )
        if key in seen:
            raise UsageError(f"{sub}: flag --{key} given more than once")
        seen[key] = value
    for key in keys:
        if key not in seen:
            raise UsageError(f"{sub}: missing required flag --{key}")
    return seen


def _int(sub: str, flags: dict[str, str], key: str) -> int:
    try:
        return int(flags[key])
    except ValueError:
        raise UsageError(f"{sub}: flag --{key} must be an integer, got {flags[key]!r}") from None


def _copy(src: str, dst: str) -> None:
    Path(dst).write_bytes(Path(src).read_bytes())


def _bench(op: str, inp: str, warmup: int, iterations: int, packet_bits: int) -> dict:
    data = Path(inp).read_bytes()
    # F (large_f) in bits; each packet occupies a byte-padded stride of ceil(F/8) bytes.
    stride = (packet_bits + 7) // 8
    packets_per_iter = (len(data) // stride) if stride else 0

    def work() -> int:
        # Stands in for a codec.
        return sum(data) & 0xFFFF

    for _ in range(warmup):
        work()
    nanos: list[int] = []
    for _ in range(iterations):
        t0 = time.perf_counter_ns()
        work()
        nanos.append(time.perf_counter_ns() - t0)
    return {
        "op": op,
        "iterations": iterations,
        "packets_per_iter": packets_per_iter,
        "nanos": nanos,
    }


def main(argv: list[str]) -> int:
    if not argv:
        print("usage: adapter.py <subcommand> [--key=value ...]", file=sys.stderr)
        return 2
    cmd = argv[0]
    try:
        if cmd == "capabilities":
            print(json.dumps(CAPABILITIES))
            return 0
        if cmd in ("compress", "decompress"):
            flags = _flags(cmd, argv[1:], ("in", "out", *PARAM_KEYS))
            if _int(cmd, flags, "packet-bits") < 1:
                raise UsageError(f"{cmd}: flag --packet-bits must be 1..=65535")
            _copy(flags["in"], flags["out"])
            return 0
        if cmd in ("conformance-compress", "conformance-decompress"):
            flags = _flags(cmd, argv[1:], ("in", "out"))
            _copy(flags["in"], flags["out"])
            return 0
        if cmd == "bench":
            flags = _flags(cmd, argv[1:], ("op", "in", "warmup", "iterations", *PARAM_KEYS))
            if flags["op"] not in ("compress", "decompress"):
                raise UsageError(f"bench: --op must be compress or decompress, got {flags['op']!r}")
            payload = _bench(
                flags["op"],
                flags["in"],
                _int(cmd, flags, "warmup"),
                _int(cmd, flags, "iterations"),
                _int(cmd, flags, "packet-bits"),
            )
            print(json.dumps(payload))
            return 0
    except UsageError as e:
        print(e, file=sys.stderr)
        return 2
    print(f"unknown subcommand: {cmd}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
