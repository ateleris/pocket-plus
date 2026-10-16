"""Tests for the shared prepare/derive orchestration, driven by the mock adapter."""

import dataclasses
from pathlib import Path

import pytest

from pocketbench import orchestrate
from pocketbench.adapter.discovery import discover_adapters
from pocketbench.config import Dataset
from pocketbench.orchestrate import ImplUnavailable

REPO = Path(__file__).resolve().parents[1]


def _mock():
    found = discover_adapters(REPO / "adapters", repo_root=REPO, variables={})
    (mock,) = [d for d in found if d.manifest.name == "mock"]
    return mock


def test_prepare_builds_and_loads_capabilities():
    prepared = orchestrate.prepare_impls(
        [_mock()], explicit=True, build=True, max_packet_bits=None
    )
    assert [p.name for p in prepared] == ["mock"]
    assert prepared[0].capabilities.limitations.startswith("identity test double")


def test_prepare_skips_unavailable_when_not_explicit():
    mock = _mock()
    messages = []
    broken = dataclasses.replace(
        mock, manifest=dataclasses.replace(mock.manifest, entrypoint="does/not/exist")
    )
    prepared = orchestrate.prepare_impls(
        [broken], explicit=False, build=False, max_packet_bits=None,
        on_message=messages.append,
    )
    assert prepared == []
    assert messages and "entrypoint not built" in messages[0]


def test_prepare_errors_on_unavailable_when_explicit():
    mock = _mock()
    broken = dataclasses.replace(
        mock, manifest=dataclasses.replace(mock.manifest, entrypoint="does/not/exist")
    )
    with pytest.raises(ImplUnavailable):
        orchestrate.prepare_impls([broken], explicit=True, build=False, max_packet_bits=None)


def test_bench_metrics_derived_for_both_ops(tmp_path):
    inp = tmp_path / "in.bin"
    inp.write_bytes(b"0123456789")  # 5 packets of 2 bytes
    ds = Dataset(name="d", input=inp, expected=None, packet_bits=16, pt=0, ft=0, rt=0, robustness=0)
    p = orchestrate.prepare_impls([_mock()], explicit=True, build=True, max_packet_bits=None)[0]
    metrics = orchestrate.bench_metrics(p, ds, warmup=2, iterations=5)
    assert {m.operation for m in metrics} == {"compress", "decompress"}
    assert all(m.num_packets == 5 and m.vector == "d" for m in metrics)
    assert [m.iterations for m in metrics] == [5, 5]


@pytest.mark.parametrize(
    ("probe_ns", "warmup", "iterations", "expected"),
    [
        (1_000_000, 10, 100, (10, 100)),     # 1 ms: 1000 fit, capped at --iterations
        (50_000_000, 10, 100, (2, 20)),      # 50 ms: 20 fit; warmup keeps the 10:100 ratio
        (300_000_000, 10, 100, (1, 5)),      # 300 ms: 4 fit, raised to the floor of 5
        (300_000_000, 10, 3, (10, 3)),       # a cap below the floor wins
        (300_000_000, 0, 100, (0, 5)),       # no warmup asked, none given
        (0, 10, 100, (10, 100)),             # too fast to measure: the cap
    ],
)
def test_budget_plan(probe_ns, warmup, iterations, expected):
    assert orchestrate.budget_plan(
        probe_ns, budget_s=1.0, warmup=warmup, iterations=iterations
    ) == expected


def _tiny_dataset(tmp_path):
    inp = tmp_path / "in.bin"
    inp.write_bytes(b"0123456789")
    return Dataset(name="d", input=inp, expected=None, packet_bits=16, pt=0, ft=0, rt=0, robustness=0)


def test_bench_metrics_time_budget_sizes_the_iterations(tmp_path):
    p = orchestrate.prepare_impls([_mock()], explicit=True, build=True, max_packet_bits=None)[0]
    ds = _tiny_dataset(tmp_path)
    tight = orchestrate.bench_metrics(p, ds, warmup=2, iterations=20, time_budget=1e-9)
    roomy = orchestrate.bench_metrics(p, ds, warmup=2, iterations=20, time_budget=60.0)
    assert [m.iterations for m in tight] == [5, 5]
    assert [m.iterations for m in roomy] == [20, 20]


def test_build_info_from_manifest():
    p = orchestrate.prepare_impls([_mock()], explicit=True, build=True, max_packet_bits=None)[0]
    info = orchestrate.build_info(p, None)
    assert info.impl == "mock"
    assert info.language == "Python"


def test_dataset_input_frames_fields_with_schedule_flags(tmp_path):
    from pocketbench import records, schedule

    inp = tmp_path / "in.bin"
    inp.write_bytes(b"\x01\x02\x03\x04\x05")
    ds = Dataset(name="d", input=inp, expected=None, packet_bits=16, pt=2, ft=3, rt=4, robustness=1)
    data, count = orchestrate.dataset_input(ds)
    assert count == 3
    expected = records.from_fields(
        16, b"\x01\x02\x03\x04\x05", schedule.flags(3, pt=2, ft=3, rt=4, robustness=1)
    )
    assert records.decode(data) == expected
