"""Drive per-implementation adapter CLIs that speak the pocketbench contract.

The contract is three subcommands: ``capabilities`` (report text, gating nothing) and the two codec
verbs ``compress`` / ``decompress``, which read and write the record container (``records.py``).
Bench is the same verb with ``--warmup`` / ``--iterations``. Adapters are launched with cwd set to
the adapter's own folder; all data paths are absolute. The argv for each verb is built in exactly
one place here (``verb_argv``), so no other module encodes the shape.
"""

from __future__ import annotations

import json
import shlex
import statistics
import subprocess
from dataclasses import dataclass, replace
from pathlib import Path

from pocketbench import records
from pocketbench.adapter.discovery import DiscoveredAdapter, entrypoint_path, source_present
from pocketbench.benchmark import BenchMetrics, BenchRecord, derive

VERBS = ("compress", "decompress")


@dataclass(frozen=True)
class Capabilities:
    """Self-reported report text from the adapter's ``capabilities`` subcommand."""

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
    """Build the argv: ``[interpreter?] <entrypoint> <parts...>`` (all stringified)."""
    entry = str(entrypoint_path(d))
    prefix = shlex.split(d.manifest.interpreter) if d.manifest.interpreter else []
    return [*prefix, entry, *[str(p) for p in parts]]


def launch_argv(d: DiscoveredAdapter, *parts) -> list[str]:
    """Public: the argv that runs a subcommand, for callers that must wrap it."""
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
    """Run ``capabilities``; also the smoke test that the built binary speaks the contract."""
    argv = _launch(d, "capabilities")
    proc = subprocess.run(argv, cwd=d.manifest.dir, capture_output=True, text=True, timeout=timeout)
    if proc.returncode != 0:
        raise RuntimeError(f"{d.manifest.name}: capabilities failed\n{proc.stderr.strip()}")
    data = json.loads(proc.stdout)
    return Capabilities(
        build_profile=str(data.get("build_profile", "")),
        limitations=str(data.get("limitations", "")),
    )


def verb_argv(
    d: DiscoveredAdapter, verb: str, input_path: Path, output_path: Path,
    *, warmup: int | None = None, iterations: int | None = None,
) -> list[str]:
    """The argv for ``compress`` / ``decompress``."""
    if verb not in VERBS:
        raise ValueError(f"verb must be one of {VERBS}, got {verb!r}")
    flags = [f"--in={input_path}", f"--out={output_path}"]
    if warmup is not None:
        flags.append(f"--warmup={warmup}")
    if iterations is not None:
        flags.append(f"--iterations={iterations}")
    return _launch(d, verb, *flags)


@dataclass(frozen=True)
class VerbRun:
    """Outcome of one ``compress`` / ``decompress`` run."""

    returncode: int | None           # None: timed out; negative: killed by that signal
    stderr: str
    summary: dict | None             # the adapter's stdout JSON
    output: records.Container | None  # None when no usable output file was written
    output_error: str | None = None   # why the output file could not be parsed

    @property
    def completed(self) -> bool:
        """Exited 0, wrote output and processed every record."""
        return (
            self.returncode == 0 and self.output is not None and self.summary is not None
            and self.summary.get("stopped_at") is None
        )

    @property
    def reason(self) -> str:
        if self.returncode is None:
            return "timeout"
        if self.returncode < 0:
            return f"crashed: signal {-self.returncode}"
        if self.returncode != 0:
            lines = self.stderr.strip().splitlines()
            return f"exit {self.returncode}: {lines[0]}" if lines else f"exit {self.returncode}"
        if self.output_error is not None:
            return f"malformed output: {self.output_error}"
        if self.output is None:
            return "no output written"
        if self.summary is None:
            return "no summary printed"
        if self.summary.get("error"):
            return f"stopped: {self.summary['error']}"
        return "ok"


def _text(data: bytes | str | None) -> str:
    if data is None:
        return ""
    return data if isinstance(data, str) else data.decode("utf-8", "replace")


def run_verb(
    d: DiscoveredAdapter, verb: str, input_path: Path, output_path: Path,
    *, warmup: int | None = None, iterations: int | None = None, timeout: int = 300,
) -> VerbRun:
    """Run one verb to exactly ``output_path`` and collect everything it produced."""
    output_path = Path(output_path)
    output_path.unlink(missing_ok=True)
    argv = verb_argv(d, verb, input_path, output_path, warmup=warmup, iterations=iterations)
    try:
        proc = subprocess.run(argv, cwd=d.manifest.dir, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired as exc:
        return VerbRun(None, _text(exc.stderr), None, None)
    stdout = _text(proc.stdout).strip()
    try:
        summary = json.loads(stdout) if stdout else None
    except json.JSONDecodeError:
        summary = None
    if not isinstance(summary, dict):
        summary = None
    output, output_error = None, None
    if output_path.exists():
        try:
            output = records.decode(output_path.read_bytes())
        except ValueError as exc:
            output_error = str(exc)
    return VerbRun(proc.returncode, _text(proc.stderr), summary, output, output_error)


@dataclass(frozen=True)
class BenchPayload:
    """Raw timed-nanos payload of a verb run with ``--iterations``."""

    op: str
    iterations: int
    packets_per_iter: int
    nanos: list[int]


def bench(
    d: DiscoveredAdapter, verb: str, input_path: Path, output_path: Path,
    *, warmup: int, iterations: int, packets: int, timeout: int = 1800,
) -> BenchPayload:
    """Run ``verb`` with the in-process warmup + timed loop; return the raw-nanos payload.

    Core derives the metrics (see ``to_metrics``); ``packets`` is how many packets one pass covers.
    """
    run = run_verb(d, verb, input_path, output_path, warmup=warmup, iterations=iterations,
                   timeout=timeout)
    if not run.completed:
        raise RuntimeError(f"{d.manifest.name}: bench {verb} failed ({run.reason})\n{run.stderr.strip()}")
    return BenchPayload(op=verb, iterations=iterations, packets_per_iter=packets,
                        nanos=[int(n) for n in run.summary["nanos"]])


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
    return replace(derive(record, packet_bits=packet_bits), iterations=len(payload.nanos))
