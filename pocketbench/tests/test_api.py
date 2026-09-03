"""Tests for the importable PocketBench facade, driven by the mock adapter.

The facade is the single source of truth both the CLI and notebooks call. These tests build a
Config by hand (mock adapter + a synthetic 2-byte-packet dataset) so they never need the C/Rust
codecs, and exercise: structured returns, .to_dataframe(), silent-by-default + callbacks, typed
errors on bad selection/config, .ok semantics, and opt-in file writing.
"""

from __future__ import annotations

import dataclasses
from pathlib import Path

import pytest

from pocketbench.adapter.discovery import discover_adapters
from pocketbench.api import ConfigError, PocketBench, SelectionError
from pocketbench.config import Config, Dataset, Settings

REPO = Path(__file__).resolve().parents[1]


def _mock_adapter():
    found = discover_adapters(REPO / "adapters", repo_root=REPO, variables={})
    return next(d for d in found if d.manifest.name == "mock")


def _cfg(tmp_path: Path, *, adapters=None, with_dataset=True) -> Config:
    datasets: dict[str, Dataset] = {}
    if with_dataset:
        inp = tmp_path / "in.bin"
        inp.write_bytes(b"0123456789")  # 5 packets of 2 bytes
        datasets["d"] = Dataset(
            name="d", input=inp, expected=None, packet_bits=16, pt=0, ft=0, rt=0, robustness=0
        )
    return Config(
        settings=Settings(ccsds124_root=tmp_path, results_dir=tmp_path / "results"),
        datasets=datasets,
        adapters=adapters if adapters is not None else [_mock_adapter()],
        conformance_suite=None,
    )


def test_bench_returns_metrics_for_both_ops(tmp_path):
    pb = PocketBench(_cfg(tmp_path))
    run = pb.bench(warmup=2, iterations=5)
    assert {m.operation for m in run.metrics} == {"compress", "decompress"}
    assert all(m.num_packets == 5 and m.vector == "d" and m.impl == "mock" for m in run.metrics)
    assert run.iterations == 5


def test_bench_to_dataframe_shape(tmp_path):
    pb = PocketBench(_cfg(tmp_path))
    df = pb.bench(warmup=2, iterations=5).to_dataframe()
    assert len(df) == 2
    assert {"impl", "vector", "operation", "us_per_pkt", "packets_per_sec"} <= set(df.columns)


def test_validate_round_trip_ok(tmp_path):
    pb = PocketBench(_cfg(tmp_path))
    run = pb.validate()
    assert run.ok
    assert any(r.check == "round-trip" and r.passed for r in run.results)


def test_validate_to_dataframe_shape(tmp_path):
    pb = PocketBench(_cfg(tmp_path))
    df = pb.validate().to_dataframe()
    assert {"impl", "dataset", "check", "passed", "detail"} <= set(df.columns)


def test_silent_by_default_no_stdout(tmp_path, capsys):
    pb = PocketBench(_cfg(tmp_path))
    pb.bench(warmup=2, iterations=5)
    captured = capsys.readouterr()
    assert captured.out == ""
    assert captured.err == ""


def test_on_message_fires_when_unselected_impl_skipped(tmp_path):
    mock = _mock_adapter()
    broken = dataclasses.replace(
        mock, manifest=dataclasses.replace(mock.manifest, name="broken", entrypoint="does/not/exist")
    )
    pb = PocketBench(_cfg(tmp_path, adapters=[mock, broken]))
    messages: list[str] = []
    run = pb.bench(build=False, warmup=2, iterations=5, on_message=messages.append)
    assert {m.impl for m in run.metrics} == {"mock"}  # broken skipped, not fatal
    assert any("entrypoint not built" in m for m in messages)


def test_unknown_impl_raises_selection_error(tmp_path):
    pb = PocketBench(_cfg(tmp_path))
    with pytest.raises(SelectionError):
        pb.bench(impls=["cobol"])


def test_unknown_dataset_raises_selection_error(tmp_path):
    pb = PocketBench(_cfg(tmp_path))
    with pytest.raises(SelectionError):
        pb.bench(datasets=["nope"])


def test_missing_config_path_raises_config_error(tmp_path):
    with pytest.raises(ConfigError):
        PocketBench(tmp_path / "no-such-config.toml")


def test_bench_writes_nothing_without_write_report(tmp_path):
    pb = PocketBench(_cfg(tmp_path))
    pb.bench(warmup=2, iterations=5)
    results_dir = tmp_path / "results"
    assert not results_dir.exists() or list(results_dir.iterdir()) == []


def test_write_report_persists_benchmark_files(tmp_path):
    pb = PocketBench(_cfg(tmp_path))
    run = pb.bench(warmup=2, iterations=5)
    paths = pb.write_report(run)
    names = {p.name for p in paths}
    assert names == {"benchmark.md", "benchmark.json"}
    assert all(p.exists() for p in paths)
