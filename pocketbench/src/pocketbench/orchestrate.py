"""Orchestration glue between pocketbench commands and the adapter contract.

Holds what the commands share: a prepared impl (adapter + its self-reported capabilities), the
build/gate/capabilities/strict-vs-skip step, and the bench per-impl derivation, which lives here
rather than in benchmark.py to keep that module free of adapter imports.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from pocketbench import adapter
from pocketbench.adapter import Capabilities, Params
from pocketbench.adapter.discovery import DiscoveredAdapter, entrypoint_present
from pocketbench.benchmark import BenchMetrics, BuildInfo
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


def build_info(p: Prepared, max_packet_bits: int | None) -> BuildInfo:
    """Reproducibility record for one impl's build, from its manifest + version_cmd."""
    manifest = p.adapter.manifest
    bits = "" if max_packet_bits is None else max_packet_bits
    command = (
        manifest.build.format(source=p.adapter.resolved_source, max_packet_bits=bits)
        if manifest.build
        else "(none)"
    )
    return BuildInfo(
        impl=manifest.name,
        language=manifest.language or "unknown",
        toolchain=adapter.version(p.adapter),
        clean_command=None,
        build_command=command,
        build_profile=p.capabilities.build_profile,
        limitations=p.capabilities.limitations,
    )


def bench_metrics(
    p: Prepared, dataset: Dataset, *, warmup: int, iterations: int
) -> list[BenchMetrics]:
    """Run the adapter's bench for each op the impl supports and derive metrics in core."""
    params = Params(
        packet_bits=dataset.packet_bits, pt=dataset.pt, ft=dataset.ft,
        rt=dataset.rt, robustness=dataset.robustness,
    )
    metrics: list[BenchMetrics] = []
    for op in ("compress", "decompress"):
        if op not in p.capabilities.ops:
            continue

        payload = adapter.bench(
            p.adapter, op, dataset.input, params, warmup=warmup, iterations=iterations
        )
        
        metrics.append(
            adapter.to_metrics(
                payload, impl_name=p.name, vector=dataset.name, packet_bits=dataset.packet_bits
            )
        )
    return metrics
