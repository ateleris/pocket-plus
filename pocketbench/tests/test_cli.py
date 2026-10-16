"""CLI rendering tests over a canned run, so no adapter is built or executed."""

from __future__ import annotations

from pathlib import Path

import pytest
from typer.testing import CliRunner

from pocketbench import cli
from pocketbench.api import BenchRun, PocketBenchError, SweepRun
from pocketbench.benchmark import BenchMetrics
from pocketbench.sweep import SweepResult


class _Named:
    def __init__(self, name: str):
        self.name = name
        self.manifest = self


class _CannedBench:
    class config:
        select_impls = staticmethod(
            lambda names, command=None: [_Named("impl-a"), _Named("impl-b")]
        )
        select_datasets = staticmethod(lambda names: [_Named(f"set-{i}") for i in range(3)])

    calls: list[dict] = []

    def bench(self, *args, **kwargs):
        self.calls.append(kwargs)
        metrics = [
            BenchMetrics(impl="mock", vector="simple", operation=op, num_packets=10, size_bytes=900,
                         time_ms=1.0, us_per_pkt=1.0, packets_per_sec=1000.0, mb_per_sec=1.0)
            for op in ("compress", "decompress")
        ]
        return BenchRun(metrics=metrics, build_infos=[], environment={}, iterations=1)

    def sweep(self, impls, datasets=None, **kwargs):
        if not impls:
            raise PocketBenchError("sweep needs at least one implementation")
        self.calls.append(kwargs)

        def row(dataset, robustness, output_bits):
            return SweepResult(
                impl="mock", group="corpus", dataset=dataset, pt=10, ft=None, rt=50,
                robustness=robustness, packets=10, packet_bits=8, input_bits=80,
                output_bits=output_bits, ratio=80 / output_bits if output_bits else None,
                reason=None if output_bits else "stopped: codec error 3",
            )

        results = [row("corpus-a", 0, 40), row("corpus-b", 0, 20),
                   row("corpus-a", 1, 80), row("corpus-b", 1, None)]
        return SweepRun(results=results, parameters={}, environment={})

    def write_report(self, run):
        if isinstance(run, SweepRun):
            return [Path("sweep.json")]
        return Path("benchmark.md"), Path("benchmark.json")


@pytest.fixture
def bench_output(monkeypatch):
    monkeypatch.setattr(cli, "_load", lambda config: _CannedBench())

    def run(*flags: str) -> str:
        result = CliRunner().invoke(cli.app, ["bench", *flags], env={"COLUMNS": "200"})
        assert result.exit_code == 0, result.output
        return result.output

    return run


def test_bench_shows_only_the_overview_by_default(bench_output):
    out = bench_output()
    assert "Throughput Overview (MB/s)" in out
    assert "Compress: simple" not in out


@pytest.mark.parametrize("flag", ["-v", "--verbose"])
def test_bench_verbose_adds_the_per_dataset_tables(bench_output, flag):
    out = bench_output(flag)
    assert "Compress: simple" in out and "Decompress: simple" in out
    assert out.index("Decompress: simple") < out.index("Throughput Overview (MB/s)")


def test_bench_summarizes_the_selection_by_default(bench_output):
    out = bench_output()
    assert "Selection: 2 implementations, 3 datasets" in out
    assert "set-1" not in out


def test_bench_verbose_lists_the_selection(bench_output):
    out = bench_output("-v")
    assert "impl-b" in out and "set-2" in out


@pytest.mark.parametrize("command", ["validate", "profile"])
def test_commands_with_a_selection_take_verbose(command):
    assert "--verbose" in CliRunner().invoke(cli.app, [command, "--help"]).output


def test_bench_passes_the_time_budget_through(bench_output):
    _CannedBench.calls.clear()
    bench_output("--time-budget", "1.5")
    bench_output()
    assert [c["time_budget"] for c in _CannedBench.calls] == [1.5, None]


def _sweep(monkeypatch, *flags: str, exit_code: int = 0) -> str:
    monkeypatch.setattr(cli, "_load", lambda config: _CannedBench())
    result = CliRunner().invoke(cli.app, ["sweep", *flags], env={"COLUMNS": "200"})
    assert result.exit_code == exit_code, result.output
    return result.output


def test_sweep_passes_value_specs_through(monkeypatch):
    _CannedBench.calls.clear()
    _sweep(monkeypatch, "-i", "mock", "--pt", "1-3", "--ft", "off", "--robustness", "0,7")
    (call,) = _CannedBench.calls
    assert (call["pt"], call["ft"], call["rt"], call["robustness"]) == ([1, 2, 3], [None], None, [0, 7])


def test_sweep_shows_group_means_and_counts_incomplete_runs(monkeypatch):
    out = _sweep(monkeypatch, "-i", "mock")
    assert "corpus" in out and "3.00" in out and "1.00" in out
    assert "corpus-a" not in out
    assert "1 measurement did not complete" in out and "stopped: codec error 3" in out
    assert "sweep.json" in out


def test_sweep_verbose_lists_every_dataset(monkeypatch):
    out = _sweep(monkeypatch, "-i", "mock", "-v")
    assert "corpus-a" in out and "corpus-b" in out


@pytest.mark.parametrize(
    "flags",
    [("-i", "mock", "--pt", "5-1"), ("-i", "mock", "--robustness", "off"), ("--pt", "1")],
)
def test_sweep_usage_errors_exit_2(monkeypatch, flags):
    _sweep(monkeypatch, *flags, exit_code=2)
