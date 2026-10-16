"""Persist and present benchmark results.

Renders the ccsds124-style benchmark output: console tables, a ``BENCHMARK.md`` replica, and a
machine-readable JSON record. Chart and HTML rendering is stubbed.
"""

from __future__ import annotations

import json
from collections import Counter
import platform
from dataclasses import asdict
from pathlib import Path

import psutil
from rich.table import Table
from rich.text import Text

from pocketbench.benchmark import MIN_BUDGET_ITERATIONS, BenchMetrics, BuildInfo

_OPERATIONS = ("compress", "decompress")
_COLUMNS = (
    "Implementation", "Time (ms)", "Packets/sec", "µs/pkt", "Throughput (MB/s)", "Iterations",
)


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


def _impl_label(m: BenchMetrics) -> str:
    if m.build_packet_bits is None:
        return m.impl
    return f"{m.impl} (built for F ≤ {m.build_packet_bits})"


def _built_for(info: BuildInfo) -> str:
    if not info.size_specialized:
        return "any F (run time)"
    if info.packet_bits is None:
        return "as last built (--no-build)"
    return f"F ≤ {info.packet_bits} bits (compile-time)"


def _specialized_impls(build_infos: list[BuildInfo]) -> list[str]:
    return sorted({info.impl for info in build_infos if info.size_specialized})


_SPECIALIZATION_NOTE = (
    "compile the packet size in (the adapter build takes `{max_packet_bits}`), so bench rebuilds "
    "each one for every dataset size group and times it on a build sized for exactly that "
    "group: the largest F of the group, floored at 720 bits. Every such result row names the build "
    "it ran on. This is a compile-time advantage the other implementations do not take: they size "
    "their buffers at run time for any F up to 65535."
)


def _row(m: BenchMetrics) -> tuple[str, ...]:
    return (
        _impl_label(m),
        f"{m.time_ms:.3f}",
        f"{m.packets_per_sec:,.0f}",
        f"{m.us_per_pkt:.2f}",
        f"{m.mb_per_sec:.2f}",
        "-" if m.iterations is None else str(m.iterations),
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


def _overview_cells(metrics: list[BenchMetrics]) -> list[tuple[str, list[tuple[str, bool]]]]:
    """Per dataset, one (MB/s, fastest) cell per operation and impl, compress first; "-" if absent."""
    rows = []
    for vector in _ordered_vectors(metrics):
        row = []
        for operation in _OPERATIONS:
            group = {m.impl: m for m in _group(metrics, operation, vector)}
            fastest = max(group.values(), key=lambda m: m.mb_per_sec) if group else None
            for impl in _ordered_impls(metrics):
                m = group.get(impl)
                row.append((f"{m.mb_per_sec:.2f}", m is fastest) if m else ("-", False))
        rows.append((vector, row))
    return rows


def overview_table(metrics: list[BenchMetrics]) -> Table:
    """Throughput only: a row per dataset, a column per impl under each operation."""
    table = Table(title="Throughput Overview (MB/s)", header_style="bold")
    table.add_column("Dataset", justify="left")
    for operation in _OPERATIONS:
        for i, impl in enumerate(_ordered_impls(metrics)):
            group_label = operation.capitalize() if i == 0 else ""
            table.add_column(Text(f"{group_label}\n{impl}", justify="left"), justify="right")
    for vector, row in _overview_cells(metrics):
        table.add_row(vector, *(f"[yellow]{v}[/yellow]" if best else v for v, best in row))
    return table


def _overview_lines(metrics: list[BenchMetrics]) -> list[str]:
    """HTML rather than a Markdown table, which cannot span a header over a column group."""
    impls = _ordered_impls(metrics)
    groups = "".join(f'<th colspan="{len(impls)}">{op.capitalize()}</th>' for op in _OPERATIONS)
    names = "".join(f"<th>{i}</th>" for _ in _OPERATIONS for i in impls)
    lines = [
        "## Throughput Overview (MB/s)",
        "",
        "<table>",
        f'<tr><th rowspan="2">Dataset</th>{groups}</tr>',
        f"<tr>{names}</tr>",
    ]
    for vector, row in _overview_cells(metrics):
        tds = "".join(f"<td><b>{v}</b></td>" if best else f"<td>{v}</td>" for v, best in row)
        lines.append(f"<tr><td>{vector}</td>{tds}</tr>")
    lines += ["</table>", ""]
    return lines


def build_settings_table(build_infos: list[BuildInfo]) -> Table:
    """Console table of how each impl was built, so the numbers are reproducible at a glance.

    Mirrors the ``## Build Settings`` section written to benchmark.md: one row per build, so a
    size-specialized impl has a row per dataset size group.
    """
    specialized = _specialized_impls(build_infos)
    table = Table(
        title="Build Settings",
        caption=(f"{', '.join(specialized)}: rebuilt per dataset size group, a compile-time "
                 "advantage over the impls sized at run time") if specialized else None,
        header_style="bold",
    )
    table.add_column("Implementation", justify="left")
    table.add_column("Language", justify="left")
    table.add_column("Toolchain", justify="left")
    table.add_column("Built for", justify="left")
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
            _built_for(info),
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


def _build_settings_lines(build_infos: list[BuildInfo]) -> list[str]:
    """Render the build-settings section: enough to reproduce the numbers."""
    lines = ["## Build Settings", ""]
    specialized = _specialized_impls(build_infos)
    if specialized:
        lines += [f"Size-specialized builds ({', '.join(specialized)}) {_SPECIALIZATION_NOTE}", ""]
    for info in build_infos:
        heading = f"### {info.impl} ({info.language})"
        if info.size_specialized and info.packet_bits is not None:
            heading += f", built for F ≤ {info.packet_bits} bits"
        lines += [
            heading,
            "",
            f"- Toolchain: `{info.toolchain}`",
            f"- Built for: {_built_for(info)}",
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


def _iterations_line(iterations: int, time_budget: float | None) -> str:
    if time_budget is None:
        return f"- Iterations: {iterations}"
    low = min(MIN_BUDGET_ITERATIONS, iterations)
    return (f"- Iterations: time budget of {time_budget:g} s per measurement ({low} to {iterations} "
            "timed iterations, sized from one probed iteration, warmup scaled to match; each row "
            "states its count)")


def write_benchmark_markdown(
    metrics: list[BenchMetrics],
    environment: dict,
    build_infos: list[BuildInfo],
    iterations: int,
    results_dir: Path,
    *,
    time_budget: float | None = None,
) -> Path:
    """Write a BENCHMARK.md-style report replicating the ccsds124 layout."""
    results_dir.mkdir(parents=True, exist_ok=True)
    lines = ["# CCSDS 124.0-B-1 Benchmark Results (pocketbench)", ""]
    lines += ["## Environment", ""]
    lines += ["| Property | Value |", "|---|---|"]
    for key, value in environment.items():
        lines.append(f"| {key} | {value} |")
    lines.append("")
    lines += _overview_lines(metrics)
    lines += _build_settings_lines(build_infos)
    for title, operation in (("Compression Results", "compress"),
                             ("Decompression Results", "decompress")):
        lines += [f"## {title}", ""]
        for vector in _ordered_vectors(metrics):
            lines += _md_table(metrics, operation, vector)
    lines += ["## Methodology", "",
              _iterations_line(iterations, time_budget),
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
    results_dir: Path,
    *,
    time_budget: float | None = None,
) -> Path:
    """Write the machine-readable benchmark record."""
    results_dir.mkdir(parents=True, exist_ok=True)
    payload = {
        "environment": environment,
        "iterations": iterations,
        "time_budget": time_budget,
        "build_settings": [asdict(b) for b in build_infos],
        "results": [asdict(m) for m in metrics],
    }
    path = results_dir / "benchmark.json"
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    return path


def _param(value: int | None) -> str:
    return "off" if value is None else str(value)


def _ordered(values) -> list:
    return list(dict.fromkeys(values))


def sweep_table(results: list) -> Table:
    """A row per impl and configuration, a column per group: the mean of its datasets' ratios."""
    groups = _ordered(r.group for r in results)
    table = Table(title="Compression ratio (mean per group, bits in / bits out)", header_style="bold")
    for col in ("Implementation", "pt", "ft", "rt", "R"):
        table.add_column(col, justify="left" if col == "Implementation" else "right")
    for group in groups:
        table.add_column(group, justify="right")
    keys = _ordered((r.impl, r.pt, r.ft, r.rt, r.robustness) for r in results)
    for key in keys:
        cells = []
        for group in groups:
            ratios = [r.ratio for r in results
                      if (r.impl, r.pt, r.ft, r.rt, r.robustness) == key
                      and r.group == group and r.ratio is not None]
            cells.append(f"{sum(ratios) / len(ratios):.2f}" if ratios else "-")
        impl, *params = key
        table.add_row(impl, *map(_param, params), *cells)
    return table


def sweep_detail_table(results: list) -> Table:
    """Every measurement: a row per impl, dataset and configuration."""
    table = Table(title="Compression ratio per dataset", header_style="bold")
    for col in ("Implementation", "Dataset", "pt", "ft", "rt", "R", "Ratio"):
        table.add_column(col, justify="left" if col in ("Implementation", "Dataset") else "right")
    for r in results:
        table.add_row(r.impl, r.dataset, _param(r.pt), _param(r.ft), _param(r.rt),
                      str(r.robustness), f"{r.ratio:.2f}" if r.ratio is not None else r.reason)
    return table


def sweep_incomplete_lines(results: list) -> list[str]:
    """How many measurements did not complete, and why (they are left out of the means)."""
    reasons = Counter(r.reason for r in results if r.reason is not None)
    total = sum(reasons.values())
    if not total:
        return []
    lines = [f"{total} measurement{'' if total == 1 else 's'} did not complete "
             "(left out of the means):"]
    lines += [f"  {count} x {reason}" for reason, count in reasons.most_common()]
    return lines


def write_sweep_json(results: list, parameters: dict, environment: dict, results_dir: Path) -> Path:
    """Write the machine-readable sweep record."""
    results_dir.mkdir(parents=True, exist_ok=True)
    payload = {
        "environment": environment,
        "parameters": parameters,
        "results": [asdict(r) for r in results],
    }
    path = results_dir / "sweep.json"
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
