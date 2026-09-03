"""Drive per-implementation adapter CLIs that speak the pocketbench contract.

pocketbench core owns iteration/warmup counts, dataset staging, metric derivation, aggregation and
conformance grading; the adapter owns param mapping and the codec calls, and must write its output at
the exact path core names. Core talks only to the contract and never knows the codec's language.
Adapters are launched with cwd set to the adapter's own folder; all data paths are absolute.

Every subcommand takes keyed `--key=value` flags, and the argv for each verb is built in exactly
one place here (`oneshot_argv` / `conformance_argv` / `bench`), so no other module encodes the shape.
"""

from __future__ import annotations

import json
import shlex
import statistics
import subprocess
from dataclasses import dataclass
from pathlib import Path

from pocketbench.adapter.discovery import DiscoveredAdapter, entrypoint_path, source_present
from pocketbench.benchmark import BenchMetrics, BenchRecord, derive


@dataclass(frozen=True)
class Capabilities:
    """Self-reported metadata from the adapter's `capabilities` subcommand."""

    ops: list[str]
    timing_tier: str  # "in_process" | "subprocess"
    reference_conformant: bool
    conformance_compress: bool
    conformance_decompress: bool
    param_schedule: str
    # Optional (default ""): a minimal adapter such as the mock reports neither.
    build_profile: str = ""
    limitations: str = ""


@dataclass(frozen=True)
class AdapterRunResult:
    """Outcome of one adapter subprocess invocation."""

    ok: bool
    args: list[str]
    returncode: int
    stdout: str
    stderr: str


def _launch(d: DiscoveredAdapter, *parts) -> list[str]:
    """Build the argv: `[interpreter?] <entrypoint> <parts...>` (all stringified)."""
    entry = str(entrypoint_path(d))
    prefix = shlex.split(d.manifest.interpreter) if d.manifest.interpreter else []
    return [*prefix, entry, *[str(p) for p in parts]]


def launch_argv(d: DiscoveredAdapter, *parts) -> list[str]:
    """Public: the argv that runs a subcommand, for callers (e.g. profiling) that must wrap it."""
    return _launch(d, *parts)


def version(d: DiscoveredAdapter, *, timeout: int = 30) -> str:
    """First line of the adapter's version_cmd output (run in the adapter dir), or 'unknown'."""
    if not d.manifest.version_cmd:
        return "unknown"
    try:
        proc = subprocess.run(
            shlex.split(d.manifest.version_cmd),
            cwd=d.manifest.dir, capture_output=True, text=True, timeout=timeout,
        )
    except (OSError, subprocess.SubprocessError):
        return "unknown"
    out = (proc.stdout or proc.stderr).strip().splitlines()
    return out[0] if out else "unknown"


def _run(d: DiscoveredAdapter, argv: list[str], timeout: int) -> subprocess.CompletedProcess:
    return subprocess.run(
        argv, cwd=d.manifest.dir, capture_output=True, text=True, timeout=timeout
    )


def build(
    d: DiscoveredAdapter, *, max_packet_bits: int | None = None, timeout: int = 600
) -> AdapterRunResult:
    """Build the wrapper in its own folder. Gate 1: refuse if the codec source is absent."""
    if not source_present(d):
        target = d.error or (str(d.resolved_source) if d.resolved_source else "<unset>")
        return AdapterRunResult(
            False, [], -1, "", f"{d.manifest.name}: implementation not found at {target}"
        )
    if not d.manifest.build:
        return AdapterRunResult(True, [], 0, "", "")
    bits = "" if max_packet_bits is None else max_packet_bits
    command = d.manifest.build.format(source=d.resolved_source, max_packet_bits=bits)
    argv = shlex.split(command)
    proc = subprocess.run(
        argv, cwd=d.manifest.dir, capture_output=True, text=True, timeout=timeout
    )
    return AdapterRunResult(proc.returncode == 0, argv, proc.returncode, proc.stdout, proc.stderr)


def capabilities(d: DiscoveredAdapter, *, timeout: int = 30) -> Capabilities:
    """Run `capabilities` and parse the self-reported metadata JSON."""
    argv = _launch(d, "capabilities")
    proc = _run(d, argv, timeout)
    if proc.returncode != 0:
        raise RuntimeError(f"{d.manifest.name}: capabilities failed\n{proc.stderr.strip()}")
    data = json.loads(proc.stdout)
    return Capabilities(
        ops=list(data["ops"]),
        timing_tier=data["timing_tier"],
        reference_conformant=bool(data["reference_conformant"]),
        conformance_compress=bool(data["conformance_compress"]),
        conformance_decompress=bool(data["conformance_decompress"]),
        param_schedule=data["param_schedule"],
        build_profile=str(data.get("build_profile", "")),
        limitations=str(data.get("limitations", "")),
    )


@dataclass(frozen=True)
class Params:
    """The uniform per-packet parameter set; a codec ignores any it does not use."""

    packet_bits: int  # F (large_f) in bits; the adapter derives the byte stride as ceil(F/8)
    pt: int
    ft: int
    rt: int
    robustness: int


def _param_flags(params: Params) -> list[str]:
    """The uniform parameter set as `--key=value` flags.

    An adapter must reject a flag it does not know (exit 2), so adding a parameter here is a
    contract change that every adapter not carrying it reports by name.
    """
    return [
        f"--packet-bits={params.packet_bits}",
        f"--pt={params.pt}",
        f"--ft={params.ft}",
        f"--rt={params.rt}",
        f"--robustness={params.robustness}",
    ]


def oneshot_argv(
    d: DiscoveredAdapter, sub: str, params: Params, input_path: Path, output_path: Path
) -> list[str]:
    """The argv for `compress`/`decompress`, for callers (profiling) that must wrap it."""
    return _launch(d, sub, f"--in={input_path}", f"--out={output_path}", *_param_flags(params))


def conformance_argv(
    d: DiscoveredAdapter, sub: str, input_path: Path, output_path: Path
) -> list[str]:
    """The argv for one `conformance-*` vector, for callers that must wrap it."""
    return _launch(d, sub, f"--in={input_path}", f"--out={output_path}")


def _one_shot(
    d: DiscoveredAdapter, sub: str, params: Params, input_path: Path, output_path: Path, timeout: int
) -> AdapterRunResult:
    """Run a params-carrying one-shot op (compress/decompress) to an exact output path."""
    argv = oneshot_argv(d, sub, params, input_path, output_path)
    proc = _run(d, argv, timeout)
    ok = proc.returncode == 0 and Path(output_path).exists()
    return AdapterRunResult(ok, argv, proc.returncode, proc.stdout, proc.stderr)


def compress(
    d: DiscoveredAdapter, params: Params, input_path: Path, output_path: Path, *, timeout: int = 300
) -> AdapterRunResult:
    """Compress ``input_path`` to exactly ``output_path``."""
    return _one_shot(d, "compress", params, input_path, output_path, timeout)


def decompress(
    d: DiscoveredAdapter, params: Params, input_path: Path, output_path: Path, *, timeout: int = 300
) -> AdapterRunResult:
    """Decompress ``input_path`` to exactly ``output_path``."""
    return _one_shot(d, "decompress", params, input_path, output_path, timeout)


def _conformance(
    d: DiscoveredAdapter, sub: str, input_path: Path, output_path: Path, timeout: int
) -> AdapterRunResult:
    argv = conformance_argv(d, sub, input_path, output_path)
    proc = _run(d, argv, timeout)
    ok = proc.returncode == 0 and Path(output_path).exists()
    return AdapterRunResult(ok, argv, proc.returncode, proc.stdout, proc.stderr)


def conformance_compress(
    d: DiscoveredAdapter, input_path: Path, output_path: Path, *, timeout: int = 60
) -> AdapterRunResult:
    """Run one UAB/CNES encoder vector through the adapter."""
    return _conformance(d, "conformance-compress", input_path, output_path, timeout)


def conformance_decompress(
    d: DiscoveredAdapter, input_path: Path, output_path: Path, *, timeout: int = 60
) -> AdapterRunResult:
    """Run one UAB/CNES decoder vector through the adapter."""
    return _conformance(d, "conformance-decompress", input_path, output_path, timeout)


@dataclass(frozen=True)
class BenchPayload:
    """Raw timed-nanos payload from the adapter's `bench` subcommand."""

    op: str
    iterations: int
    packets_per_iter: int
    nanos: list[int]


def bench(
    d: DiscoveredAdapter,
    op: str,
    input_path: Path,
    params: Params,
    *,
    warmup: int,
    iterations: int,
    timeout: int = 1800,
) -> BenchPayload:
    """Run the adapter's in-process bench loop; return the raw-nanos payload.

    The adapter loads ``input_path`` once, runs ``op`` ``warmup`` times untimed and ``iterations``
    times timed, and prints the payload as JSON. Core derives metrics (see ``to_metrics``).
    """
    argv = _launch(
        d, "bench", f"--op={op}", f"--in={input_path}",
        f"--warmup={warmup}", f"--iterations={iterations}", *_param_flags(params),
    )
    proc = _run(d, argv, timeout)
    if proc.returncode != 0:
        raise RuntimeError(f"{d.manifest.name}: bench {op} failed\n{proc.stderr.strip()}")
    data = json.loads(proc.stdout)
    return BenchPayload(
        op=data["op"],
        iterations=int(data["iterations"]),
        packets_per_iter=int(data["packets_per_iter"]),
        nanos=[int(n) for n in data["nanos"]],
    )


def to_metrics(
    payload: BenchPayload, *, impl_name: str, vector: str, packet_bits: int
) -> BenchMetrics:
    """Derive comparable BENCHMARK metrics from a raw-nanos payload.

    Uses the median timed iteration as us/iter, then reuses ``benchmark.derive``, so every impl is
    derived in one place and no adapter derives anything itself.
    """
    us_per_iter = statistics.median(payload.nanos) / 1000.0
    record = BenchRecord(
        impl=impl_name,
        vector=vector,
        operation=payload.op,
        us_per_iter=us_per_iter,
        us_per_pkt=0.0,  # recomputed by derive
        kbps=0.0,        # recomputed by derive
        num_packets=payload.packets_per_iter,
    )
    return derive(record, packet_bits=packet_bits)
