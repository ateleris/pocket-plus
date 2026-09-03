"""Reusable contract-conformance checks for any pocketbench adapter.

Point ``assert_adapter_conforms`` at a DiscoveredAdapter to verify it honors every subcommand of
the contract: capabilities schema, exact output paths for compress/decompress and conformance, a
well-formed raw-nanos bench payload, and strict rejection of an argv it does not understand.
"""

from __future__ import annotations

import subprocess
from pathlib import Path

from pocketbench import adapter
from pocketbench.adapter import Params
from pocketbench.adapter.discovery import DiscoveredAdapter

_VALID_TIERS = {"in_process", "subprocess"}


def assert_adapter_conforms(
    d: DiscoveredAdapter, *, tmp_path: Path, packet_bits: int = 16
) -> None:
    """Assert ``d``'s adapter honors the full contract. Raises AssertionError otherwise."""
    caps = adapter.capabilities(d)
    assert set(caps.ops) <= {"compress", "decompress"}
    assert caps.timing_tier in _VALID_TIERS
    for flag in (caps.reference_conformant,
                 caps.conformance_compress, caps.conformance_decompress):
        assert isinstance(flag, bool)
    assert isinstance(caps.param_schedule, str) and caps.param_schedule

    inp = tmp_path / "conf.bin"
    stride = (packet_bits + 7) // 8  # byte-padded stride, ceil(F/8)
    inp.write_bytes(bytes((i % 256 for i in range(stride * 4))))  # 4 whole packets
    params = Params(packet_bits=packet_bits, pt=1, ft=1, rt=1, robustness=1)

    if "compress" in caps.ops:
        pkt = tmp_path / "conf.pkt"
        result = adapter.compress(d, params, inp, pkt)
        assert result.ok, f"compress failed: {result.stderr}"
        assert pkt.exists(), "compress did not write the exact output path"

        payload = adapter.bench(d, "compress", inp, params, warmup=1, iterations=3)
        assert payload.op == "compress"
        assert payload.iterations == 3
        assert len(payload.nanos) == 3
        assert payload.packets_per_iter == 4
        assert all(isinstance(n, int) and n >= 0 for n in payload.nanos)

    _assert_rejects_bad_argv(d, tmp_path=tmp_path, packet_bits=packet_bits)


def _run(d: DiscoveredAdapter, argv: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(argv, cwd=d.manifest.dir, capture_output=True, text=True, timeout=120)


def _assert_rejects(d: DiscoveredAdapter, argv: list[str], why: str) -> None:
    """Exit 2 is the contract's usage error."""
    proc = _run(d, argv)
    assert proc.returncode == 2, (
        f"{d.manifest.name}: expected exit 2 for {why}, got {proc.returncode}\n"
        f"argv: {argv}\nstderr: {proc.stderr.strip()}"
    )


def _assert_rejects_bad_argv(
    d: DiscoveredAdapter, *, tmp_path: Path, packet_bits: int
) -> None:
    """The four argv shapes a harness/adapter contract mismatch produces."""
    params = Params(packet_bits=packet_bits, pt=1, ft=1, rt=1, robustness=1)
    inp = tmp_path / "argv.bin"
    stride = (packet_bits + 7) // 8
    inp.write_bytes(bytes(stride * 2))
    out = tmp_path / "argv.out"
    good = adapter.oneshot_argv(d, "compress", params, inp, out)

    _assert_rejects(d, [*good, "--not-a-real-flag=1"], "an unknown flag")
    _assert_rejects(
        d, [a for a in good if not a.startswith("--robustness=")], "a missing required flag"
    )
    _assert_rejects(
        d,
        adapter.launch_argv(d, "compress", inp, out, packet_bits, 1, 1, 1, 1),
        "a positional argv",
    )
    _assert_rejects(d, adapter.launch_argv(d, "not-a-subcommand"), "an unknown subcommand")
