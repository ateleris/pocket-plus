"""Tests for the importable PocketBench facade, driven by the mock adapter.

The facade is the single source of truth both the CLI and notebooks call. These tests build a
Config by hand (mock adapter + a synthetic 2-byte-packet dataset) so they never need the C/Rust
codecs, and exercise: structured returns, .to_dataframe(), silent-by-default + callbacks, typed
errors on bad selection/config, .ok semantics, and opt-in file writing.
"""

from __future__ import annotations

import dataclasses
import shutil
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


def _size_specialized_mock(tmp_path: Path):
    """The mock behind a build that takes `{max_packet_bits}`, logging each size it is built for."""
    d = tmp_path / "adapters" / "sized"
    d.mkdir(parents=True)
    shutil.copy(REPO / "adapters" / "mock" / "adapter.py", d / "adapter.py")
    (d / "adapter.toml").write_text(
        'interpreter = "python"\n'
        'entrypoint = "adapter.py"\n'
        "build = \"sh -c 'echo {max_packet_bits} >> builds.log'\"\n"
    )
    found = discover_adapters(tmp_path / "adapters", repo_root=tmp_path, variables={})
    return found[0], d / "builds.log"


def _with_big_dataset(cfg: Config, tmp_path: Path) -> Config:
    big = tmp_path / "big.bin"
    big.write_bytes(bytes(8192 * 2))
    cfg.datasets["big"] = Dataset(
        name="big", input=big, expected=None, packet_bits=65535, pt=0, ft=0, rt=0, robustness=0
    )
    return cfg


def test_bench_rebuilds_size_specialized_impl_per_dataset_size(tmp_path):
    sized, log = _size_specialized_mock(tmp_path)
    cfg = _with_big_dataset(_cfg(tmp_path, adapters=[sized, _mock_adapter()]), tmp_path)
    run = PocketBench(cfg).bench(["sized", "mock"], warmup=1, iterations=1)

    # The 16-bit dataset is floored to a 720-bit build; each size is built exactly once.
    assert log.read_text().split() == ["720", "65535"]
    assert sorted((b.impl, b.packet_bits) for b in run.build_infos if b.impl == "sized") == [
        ("sized", 720), ("sized", 65535)
    ]
    assert [b.packet_bits for b in run.build_infos if b.impl == "mock"] == [None]
    built_for = {(m.impl, m.vector, m.operation): m.build_packet_bits for m in run.metrics}
    assert built_for == {
        ("sized", "d", "compress"): 720, ("sized", "d", "decompress"): 720,
        ("sized", "big", "compress"): 65535, ("sized", "big", "decompress"): 65535,
        ("mock", "d", "compress"): None, ("mock", "d", "decompress"): None,
        ("mock", "big", "compress"): None, ("mock", "big", "decompress"): None,
    }


def test_bench_builds_only_the_selected_sizes(tmp_path):
    sized, log = _size_specialized_mock(tmp_path)
    cfg = _with_big_dataset(_cfg(tmp_path, adapters=[sized]), tmp_path)
    PocketBench(cfg).bench(["sized"], ["d"], warmup=1, iterations=1)
    assert log.read_text().split() == ["720"]


def test_bench_no_build_does_not_claim_a_build_size(tmp_path):
    sized, log = _size_specialized_mock(tmp_path)
    cfg = _with_big_dataset(_cfg(tmp_path, adapters=[sized]), tmp_path)
    run = PocketBench(cfg).bench(["sized"], warmup=1, iterations=1, build=False)
    assert not log.exists()
    assert [(b.packet_bits, b.size_specialized) for b in run.build_infos] == [(None, True)]
    assert {m.build_packet_bits for m in run.metrics} == {None}


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


def test_bench_refuses_zero_iterations(tmp_path):
    from pocketbench.api import PocketBenchError

    with pytest.raises(PocketBenchError, match="iterations must be at least 1"):
        PocketBench(_cfg(tmp_path)).bench(iterations=0)


def test_conformance_passes_jobs_through(tmp_path):
    from pocketbench.config import ConformanceSuite

    data = tmp_path / "suite"
    (data / "encoder_input").mkdir(parents=True)
    (data / "encoder_input" / "encoder_sequence_0000.raw+config").write_bytes(b"\x00")
    (data / "file_list.csv").write_text("path,size,sha256\n")
    cfg = _cfg(tmp_path)
    cfg.conformance_suite = ConformanceSuite(name="s", data_dir=data, manifest=data / "file_list.csv")
    run = PocketBench(cfg).conformance(["mock"], mode="encoder", build=False, jobs=2)
    (r,) = run.results
    assert (r.total, r.failed) == (1, 1)


def test_bench_time_budget_sizes_iterations_and_is_recorded(tmp_path):
    run = PocketBench(_cfg(tmp_path)).bench(warmup=2, iterations=20, time_budget=1e-9)
    assert {m.iterations for m in run.metrics} == {5}
    assert (run.iterations, run.time_budget) == (20, 1e-9)


def test_bench_without_time_budget_runs_the_fixed_count(tmp_path):
    run = PocketBench(_cfg(tmp_path)).bench(warmup=2, iterations=7)
    assert {m.iterations for m in run.metrics} == {7}
    assert run.time_budget is None


def test_bench_refuses_a_non_positive_time_budget(tmp_path):
    from pocketbench.api import PocketBenchError

    with pytest.raises(PocketBenchError, match="time budget must be positive"):
        PocketBench(_cfg(tmp_path)).bench(time_budget=0)


def _bench_skipping_mock(tmp_path: Path):
    d = tmp_path / "adapters" / "skipper"
    d.mkdir(parents=True)
    shutil.copy(REPO / "adapters" / "mock" / "adapter.py", d / "adapter.py")
    (d / "adapter.toml").write_text(
        'interpreter = "python"\nentrypoint = "adapter.py"\nskip_commands = ["bench"]\n'
    )
    return discover_adapters(tmp_path / "adapters", repo_root=tmp_path, variables={})[0]


def test_bench_leaves_out_an_adapter_that_skips_it(tmp_path):
    cfg = _cfg(tmp_path, adapters=[_mock_adapter(), _bench_skipping_mock(tmp_path)])
    pb = PocketBench(cfg)
    assert {m.impl for m in pb.bench(warmup=1, iterations=1).metrics} == {"mock"}
    assert {m.impl for m in pb.bench(["skipper"], warmup=1, iterations=1).metrics} == {"skipper"}
    assert pb.validate(["skipper", "mock"]).ok


def test_sweep_returns_one_row_per_dataset_and_configuration(tmp_path):
    run = PocketBench(_cfg(tmp_path)).sweep(["mock"], robustness=[0, 1], ft=[None])
    assert [(r.dataset, r.robustness, r.ft) for r in run.results] == [("d", 0, None), ("d", 1, None)]
    assert run.parameters == {"ft": [None], "robustness": [0, 1]}
    df = run.to_dataframe()
    assert {"impl", "group", "dataset", "pt", "ft", "rt", "robustness", "input_bits",
            "output_bits", "ratio", "reason"} <= set(df.columns)


def test_sweep_takes_configs(tmp_path):
    run = PocketBench(_cfg(tmp_path)).sweep(["mock"], configs=({"ft": 4, "rt": 2} for _ in [0]))
    assert [(r.ft, r.rt) for r in run.results] == [(4, 2)]
    assert run.parameters == {"configs": [{"ft": 4, "rt": 2}]}


@pytest.mark.parametrize(
    ("kwargs", "message"),
    [
        ({"impls": None}, "sweep needs at least one implementation"),
        ({"pt": [1], "configs": [{"pt": 2}]}, "not both"),
        ({"pt": []}, "no values for pt"),
    ],
)
def test_sweep_refuses_bad_requests(tmp_path, kwargs, message):
    from pocketbench.api import PocketBenchError

    kwargs = {"impls": ["mock"], **kwargs}
    with pytest.raises(PocketBenchError, match=message):
        PocketBench(_cfg(tmp_path)).sweep(**kwargs)


def test_write_report_persists_the_sweep(tmp_path):
    import json

    pb = PocketBench(_cfg(tmp_path))
    (path,) = pb.write_report(pb.sweep(["mock"], robustness=[2]))
    payload = json.loads(path.read_text())
    assert path.name == "sweep.json"
    assert payload["parameters"] == {"robustness": [2]}
    assert payload["results"][0]["robustness"] == 2
