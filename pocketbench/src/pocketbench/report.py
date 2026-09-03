"""Persist and present benchmark results.

Renders the ccsds124-style benchmark output: console tables, a ``BENCHMARK.md`` replica, and a
machine-readable JSON record. Chart and HTML rendering is stubbed.
"""

from __future__ import annotations

import json
import platform
from dataclasses import asdict
from pathlib import Path

import psutil
from rich.table import Table

from pocketbench.benchmark import BenchMetrics, BuildInfo

_OPERATIONS = ("compress", "decompress")
_COLUMNS = ("Implementation", "Time (ms)", "Packets/sec", "µs/pkt", "Throughput (MB/s)")


def collect_environment() -> dict:
    """Capture host details for the benchmark header (best effort)."""
    freq = None
    try:
        freq = psutil.cpu_freq()
    except Exception:  # noqa: BLE001 - cpu_freq is unavailable on some hosts
        freq = None
    return {
        "os": f"{platform.system()} {platform.release()}",
        "arch": platform.machine(),
        "processor": platform.processor() or platform.machine(),
        "cpu_count": psutil.cpu_count(logical=True),
        "cpu_mhz": round(freq.max, 0) if freq and freq.max else None,
        "memory_gb": round(psutil.virtual_memory().total / 1024**3, 1),
        "python": platform.python_version(),
    }


def _ordered_vectors(metrics: list[BenchMetrics]) -> list[str]:
    seen: list[str] = []
    for m in metrics:
        if m.vector not in seen:
            seen.append(m.vector)
    return seen


def _ordered_impls(metrics: list[BenchMetrics]) -> list[str]:
    seen: list[str] = []
    for m in metrics:
        if m.impl not in seen:
            seen.append(m.impl)
    return seen


def _row(m: BenchMetrics) -> tuple[str, ...]:
    return (
        m.impl,
        f"{m.time_ms:.3f}",
        f"{m.packets_per_sec:,.0f}",
        f"{m.us_per_pkt:.2f}",
        f"{m.mb_per_sec:.2f}",
    )


def _group(metrics: list[BenchMetrics], operation: str, vector: str) -> list[BenchMetrics]:
    return [m for m in metrics if m.operation == operation and m.vector == vector]


def benchmark_tables(metrics: list[BenchMetrics]) -> list[Table]:
    """One rich table per (operation, vector), rows per implementation."""
    tables: list[Table] = []
    for operation in _OPERATIONS:
        for vector in _ordered_vectors(metrics):
            group = _group(metrics, operation, vector)
            if not group:
                continue
            size_kb = group[0].size_bytes / 1000  # decimal KB, as upstream BENCHMARK.md
            table = Table(
                title=f"{operation.capitalize()}: {vector} "
                f"({group[0].num_packets} packets, {size_kb:.1f} KB)",
                header_style="bold",
            )
            for col in _COLUMNS:
                table.add_column(col, justify="left" if col == _COLUMNS[0] else "right")
            # Highlight the fastest implementation for this (operation, dataset) in yellow. Within a
            # group every row shares num_packets/size_bytes, so packets_per_sec ranks speed directly.
            fastest = max(group, key=lambda m: m.packets_per_sec) if group else None
            for m in group:
                table.add_row(*_row(m), style="yellow" if m is fastest else None)
            tables.append(table)
    return tables


def build_settings_table(build_infos: list[BuildInfo], max_packet_bits: int) -> Table:
    """Console table of how each impl was built, so the numbers are reproducible at a glance.

    Mirrors the ``## Build Settings`` section written to benchmark.md.
    """
    table = Table(
        title=f"Build Settings (packet size {max_packet_bits} bits / {max_packet_bits // 8} bytes)",
        header_style="bold",
    )
    table.add_column("Implementation", justify="left")
    table.add_column("Language", justify="left")
    table.add_column("Toolchain", justify="left")
    # Fold rather than ellipsize: the build command, optimization profile and limitations are the
    # reproducibility payload, so they must stay fully readable even when the terminal is narrow.
    table.add_column("Build command", justify="left", overflow="fold")
    table.add_column("Optimization", justify="left", overflow="fold")
    table.add_column("Limitations", justify="left", overflow="fold")
    for info in build_infos:
        table.add_row(
            info.impl,
            info.language,
            info.toolchain,
            info.build_command,
            info.build_profile or "-",
            info.limitations or "-",
        )
    return table


def _md_table(metrics: list[BenchMetrics], operation: str, vector: str) -> list[str]:
    group = _group(metrics, operation, vector)
    if not group:
        return []
    size_kb = group[0].size_bytes / 1000  # decimal KB, as upstream BENCHMARK.md
    lines = [
        f"### {operation.capitalize()}: {vector} "
        f"({group[0].num_packets} packets, {size_kb:.1f} KB)",
        "",
        "| " + " | ".join(_COLUMNS) + " |",
        "|" + "|".join(["---"] * len(_COLUMNS)) + "|",
    ]
    lines += ["| " + " | ".join(_row(m)) + " |" for m in group]
    lines.append("")
    return lines


def _build_settings_lines(build_infos: list[BuildInfo], max_packet_bits: int) -> list[str]:
    """Render the build-settings section: enough to reproduce the numbers."""
    lines = ["## Build Settings", "",
             f"Packet size: {max_packet_bits} bits ({max_packet_bits // 8} bytes). "
             "The C build sizes `CCSDS124_MAX_PACKET_LENGTH` to this value; leaving "
             "its 65535-bit default inflates the per-packet output buffer to ~96 KB "
             "and slows compression ~5x.", ""]
    for info in build_infos:
        lines += [
            f"### {info.impl} ({info.language})",
            "",
            f"- Toolchain: `{info.toolchain}`",
        ]
        if info.clean_command:
            lines.append(f"- Clean: `{info.clean_command}`")
        lines.append(f"- Build: `{info.build_command}`")
        if info.build_profile:
            lines.append(f"- Optimization: `{info.build_profile}`")
        if info.limitations:
            lines.append(f"- Limitations: {info.limitations}")
        lines.append("")
    return lines


def write_benchmark_markdown(
    metrics: list[BenchMetrics],
    environment: dict,
    build_infos: list[BuildInfo],
    iterations: int,
    max_packet_bits: int,
    results_dir: Path,
) -> Path:
    """Write a BENCHMARK.md-style report replicating the ccsds124 layout."""
    results_dir.mkdir(parents=True, exist_ok=True)
    lines = ["# CCSDS 124.0-B-1 Benchmark Results (pocketbench)", ""]
    lines += ["## Environment", ""]
    lines += ["| Property | Value |", "|---|---|"]
    for key, value in environment.items():
        lines.append(f"| {key} | {value} |")
    lines.append("")
    lines += _build_settings_lines(build_infos, max_packet_bits)
    for title, operation in (("Compression Results", "compress"),
                             ("Decompression Results", "decompress")):
        lines += [f"## {title}", ""]
        for vector in _ordered_vectors(metrics):
            lines += _md_table(metrics, operation, vector)
    lines += ["## Methodology", "",
              f"- Iterations: {iterations}",
              f"- Packet size: {max_packet_bits} bits ({max_packet_bits // 8} bytes)",
              "- Timing: in-process wall clock reported by each implementation's bench binary",
              "- Metrics derived as in ccsds124 scripts/benchmark.sh", ""]
    path = results_dir / "benchmark.md"
    path.write_text("\n".join(lines), encoding="utf-8")
    return path


def write_json(
    metrics: list[BenchMetrics],
    environment: dict,
    build_infos: list[BuildInfo],
    iterations: int,
    max_packet_bits: int,
    results_dir: Path,
) -> Path:
    """Write the machine-readable benchmark record."""
    results_dir.mkdir(parents=True, exist_ok=True)
    payload = {
        "environment": environment,
        "iterations": iterations,
        "packet_size_bits": max_packet_bits,
        "build_settings": [asdict(b) for b in build_infos],
        "results": [asdict(m) for m in metrics],
    }
    path = results_dir / "benchmark.json"
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    return path


def _fmt_bytes(n: int) -> str:
    if n >= 1024**2:
        return f"{n / 1024**2:.2f} MB"
    if n >= 1024:
        return f"{n / 1024:.1f} KB"
    return f"{n} B"


def profile_tables(results: list) -> list[Table]:
    """One rich table per operation for memory-profile results."""
    tables: list[Table] = []
    for operation in ("compress", "decompress"):
        group = [r for r in results if r.operation == operation]
        if not group:
            continue
        table = Table(title=f"Memory: {operation}", header_style="bold")
        cols = ["Implementation", "Dataset", "Input", "Output"]
        if operation == "compress":
            cols.append("Ratio")
        cols.append("Peak RSS")
        for col in cols:
            table.add_column(col, justify="left" if col in ("Implementation", "Dataset") else "right")
        for r in group:
            row = [r.impl, r.dataset, _fmt_bytes(r.input_bytes), _fmt_bytes(r.output_bytes)]
            if operation == "compress":
                row.append(f"{r.ratio:.2f}x" if r.ratio else "-")
            row.append(_fmt_bytes(r.peak_rss_bytes))
            table.add_row(*row)
        tables.append(table)
    return tables


def write_profile_json(results: list, environment: dict, runs: int, results_dir: Path) -> Path:
    """Write the machine-readable memory-profile record."""
    results_dir.mkdir(parents=True, exist_ok=True)
    payload = {
        "environment": environment,
        "runs": runs,
        "note": "peak_rss_bytes is whole-process peak RSS (max over runs); use bench for speed",
        "results": [asdict(r) for r in results],
    }
    path = results_dir / "profile.json"
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    return path


def render_charts(results: dict, results_dir: Path) -> list[Path]:
    raise NotImplementedError("report.render_charts: implemented in a later increment")


def render_html(results: dict, results_dir: Path) -> Path:
    raise NotImplementedError("report.render_html: implemented in a later increment")
