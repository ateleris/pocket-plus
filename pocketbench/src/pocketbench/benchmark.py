"""Pure-logic benchmark types and metric derivation.

Metric math shared by the adapter driver (``adapter.to_metrics``), the bench orchestration
(``orchestrate.bench_metrics``), and reporting (``report``). This module is deliberately free of
subprocess / adapter dependencies: ``adapter/driver.py`` imports ``derive`` from here, so importing
the adapter back would cycle.
"""

from __future__ import annotations

from dataclasses import dataclass

MIB = 1_048_576


@dataclass
class BenchRecord:
    """One measurement (median timed iteration), before metric derivation."""

    impl: str
    vector: str
    operation: str  # "compress" | "decompress"
    us_per_iter: float
    us_per_pkt: float
    kbps: float
    num_packets: int


@dataclass
class BuildInfo:
    """How an implementation's adapter was built (for reproducibility)."""

    impl: str
    language: str
    toolchain: str
    clean_command: str | None
    build_command: str
    build_profile: str = ""  # optimization flags / release profile (self-reported)
    limitations: str = ""    # build constraints, e.g. supported packet-size range (self-reported)


@dataclass
class BenchMetrics:
    """Derived, comparable metrics for one measurement (BENCHMARK.md columns)."""

    impl: str
    vector: str
    operation: str
    num_packets: int
    size_bytes: int
    time_ms: float
    us_per_pkt: float
    packets_per_sec: float
    mb_per_sec: float


def derive(record: BenchRecord, packet_bits: int) -> BenchMetrics:
    """Compute BENCHMARK.md metrics from a raw record (matches benchmark.sh).

    Throughput counts real bytes processed: each packet occupies a byte-padded stride of
    ceil(packet_bits / 8) bytes, so ``size_bytes`` uses that stride (equals packet_bits/8 when F
    is byte-aligned).
    """
    stride_bytes = (packet_bits + 7) // 8
    size_bytes = record.num_packets * stride_bytes
    us = record.us_per_iter
    return BenchMetrics(
        impl=record.impl,
        vector=record.vector,
        operation=record.operation,
        num_packets=record.num_packets,
        size_bytes=size_bytes,
        time_ms=us / 1000.0,
        us_per_pkt=us / record.num_packets if record.num_packets else 0.0,
        packets_per_sec=record.num_packets * 1_000_000 / us if us else 0.0,
        mb_per_sec=size_bytes * 1_000_000 / (us * MIB) if us else 0.0,
    )
