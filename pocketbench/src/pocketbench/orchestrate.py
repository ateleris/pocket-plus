"""Orchestration glue between pocketbench commands and the adapter contract.

Holds what the commands share: a prepared impl (adapter + its self-reported capabilities), the
build/gate/capabilities/strict-vs-skip step, and the bench per-impl derivation, which lives here
rather than in benchmark.py to keep that module free of adapter imports.
"""

from __future__ import annotations

import math
import tempfile
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from pocketbench import adapter, records, schedule
from pocketbench.adapter import Capabilities
from pocketbench.adapter.discovery import DiscoveredAdapter, entrypoint_present
from pocketbench.benchmark import MIN_BUDGET_ITERATIONS, BenchMetrics, BuildInfo
from pocketbench.config import Dataset


@dataclass(frozen=True)
class Prepared:
    """A built, available adapter plus its self-reported capabilities."""

    adapter: DiscoveredAdapter
    capabilities: Capabilities

    @property
    def name(self) -> str:
        return self.adapter.manifest.name


class ImplUnavailable(RuntimeError):
    """A selected impl could not be built or run (gate 1 or gate 2)."""


def dataset_input(ds: Dataset) -> tuple[bytes, int]:
    """The encoded compress input for ``ds`` and its packet count: the raw fields, each with the
    flag byte of the reference pt/ft/rt schedule, behind an all-zero initial mask."""
    data = ds.input.read_bytes()
    count = -(-len(data) // records.payload_bytes(ds.packet_bits))
    flags = schedule.flags(count, pt=ds.pt, ft=ds.ft, rt=ds.rt, robustness=ds.robustness)
    return records.encode(records.from_fields(ds.packet_bits, data, flags)), count


def prepare_impls(
    adapters: list[DiscoveredAdapter],
    *,
    explicit: bool,
    build: bool,
    max_packet_bits: int | None,
    on_message: Callable[[str], None] | None = None,
) -> list[Prepared]:
    """Build (gate 1) or verify the entrypoint (gate 2), then load capabilities.

    ``explicit`` is whether the user named impls with --impl. An unavailable impl raises
    ImplUnavailable when explicit (so CI / a deliberate run fails hard) and is skipped with a
    message otherwise. Returns the Prepared list of available impls.
    """
    prepared: list[Prepared] = []
    for d in adapters:
        reason: str | None = None
        if build:
            result = adapter.build(d, max_packet_bits=max_packet_bits)
            if not result.ok:
                reason = result.stderr.strip() or f"build failed (exit {result.returncode})"
        elif not entrypoint_present(d):
            reason = f"entrypoint not built at {d.manifest.entrypoint} (drop --no-build)"
        if reason is None:
            caps = adapter.capabilities(d)
            prepared.append(Prepared(adapter=d, capabilities=caps))
            continue
        message = f"{d.manifest.name}: {reason}"
        if explicit:
            raise ImplUnavailable(message)
        if on_message:
            on_message(message)
    return prepared


def build_info(p: Prepared, packet_bits: int | None) -> BuildInfo:
    """Reproducibility record for one impl's build, from its manifest + version_cmd.

    ``packet_bits`` is the F a size-specialized build was compiled for, or None when it was not
    rebuilt (``--no-build``) or the impl is not size-specialized.
    """
    manifest = p.adapter.manifest
    if not manifest.build:
        command = "(none)"
    elif manifest.size_specialized and packet_bits is None:
        command = "(not rebuilt: --no-build)"
    else:
        bits = "" if packet_bits is None else packet_bits
        command = manifest.build.format(source=p.adapter.resolved_source, max_packet_bits=bits)
    return BuildInfo(
        impl=manifest.name,
        language=manifest.language or "unknown",
        toolchain=adapter.version(p.adapter),
        clean_command=None,
        build_command=command,
        build_profile=p.capabilities.build_profile,
        limitations=p.capabilities.limitations,
        size_specialized=manifest.size_specialized,
        packet_bits=packet_bits if manifest.size_specialized else None,
    )


def budget_plan(probe_ns: int, *, budget_s: float, warmup: int, iterations: int) -> tuple[int, int]:
    """(warmup, iterations) for one measurement under a time budget, from one probed iteration.

    As many timed iterations as fit in ``budget_s``, at least MIN_BUDGET_ITERATIONS and at most
    ``iterations``; warmup keeps the requested warmup:iterations ratio, but at least 1 if asked for.
    """
    fit = math.ceil(budget_s * 1e9 / probe_ns) if probe_ns > 0 else iterations
    n = min(iterations, max(MIN_BUDGET_ITERATIONS, fit))
    w = max(1, math.ceil(warmup * n / iterations)) if warmup > 0 else 0
    return w, n


def bench_metrics(
    p: Prepared, dataset: Dataset, *, warmup: int, iterations: int,
    time_budget: float | None = None,
) -> list[BenchMetrics]:
    """Time compress, then decompress of that compress output, and derive the metrics in core.

    With ``time_budget`` (seconds), each verb is probed with one warmup and one timed iteration and
    then run with the counts :func:`budget_plan` derives from that probe.
    """
    data, count = dataset_input(dataset)
    metrics: list[BenchMetrics] = []
    with tempfile.TemporaryDirectory(prefix="pocketbench-bench-") as tmp:
        inp, packets, fields = (Path(tmp) / n for n in ("in.rec", "packets.rec", "fields.rec"))
        inp.write_bytes(data)
        for verb, src, dst in (("compress", inp, packets), ("decompress", packets, fields)):
            w, n = warmup, iterations
            if time_budget is not None:
                probe = adapter.bench(p.adapter, verb, src, dst, warmup=1, iterations=1,
                                      packets=count)
                w, n = budget_plan(probe.nanos[0], budget_s=time_budget, warmup=warmup,
                                   iterations=iterations)
            payload = adapter.bench(p.adapter, verb, src, dst, warmup=w, iterations=n,
                                    packets=count)
            metrics.append(adapter.to_metrics(
                payload, impl_name=p.name, vector=dataset.name, packet_bits=dataset.packet_bits
            ))
    return metrics
