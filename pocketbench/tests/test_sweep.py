"""The parameter sweep core, driven by the mock identity adapter (no codec needed)."""

from __future__ import annotations

from pathlib import Path

import pytest

from pocketbench import orchestrate, sweep
from pocketbench.adapter.discovery import discover_adapters
from pocketbench.config import Dataset

REPO = Path(__file__).resolve().parents[1]


@pytest.mark.parametrize(
    ("spec", "allow_off", "expected"),
    [
        ("7", True, [7]),
        ("5,10", True, [5, 10]),
        ("1-3", True, [1, 2, 3]),
        ("off", True, [None]),
        ("off,5,10-12", True, [None, 5, 10, 11, 12]),
        (" 0 , 2 ", False, [0, 2]),
    ],
)
def test_parse_values(spec, allow_off, expected):
    assert sweep.parse_values(spec, allow_off=allow_off) == expected


@pytest.mark.parametrize(
    ("spec", "allow_off"),
    [("", True), (",", True), ("5-1", True), ("x", True), ("1-", True), ("off", False), ("-3", True)],
)
def test_parse_values_rejects(spec, allow_off):
    with pytest.raises(ValueError):
        sweep.parse_values(spec, allow_off=allow_off)


def test_expand_crosses_every_list_in_parameter_order():
    assert sweep.expand(robustness=[0, 1], pt=[5, None]) == [
        {"pt": 5, "robustness": 0}, {"pt": 5, "robustness": 1},
        {"pt": None, "robustness": 0}, {"pt": None, "robustness": 1},
    ]


def test_expand_without_parameters_is_one_configuration():
    assert sweep.expand() == [{}]


def test_expand_passes_configs_through():
    configs = [{"pt": 10, "ft": 40, "rt": 20}, {"robustness": 2}]
    assert sweep.expand(configs=configs) == configs


@pytest.mark.parametrize(
    "kwargs",
    [
        {"pt": [1], "configs": [{"pt": 2}]},
        {"pt": []},
        {"configs": [{"pt": 1, "speed": 2}]},
    ],
)
def test_expand_rejects(kwargs):
    with pytest.raises(ValueError):
        sweep.expand(**kwargs)


def _mock():
    found = discover_adapters(REPO / "adapters", repo_root=REPO, variables={})
    (mock,) = [d for d in found if d.manifest.name == "mock"]
    return orchestrate.prepare_impls([mock], explicit=True, build=True, max_packet_bits=None)[0]


def _dataset(tmp_path: Path, name: str, data: bytes, packet_bits: int, group: str = "") -> Dataset:
    inp = tmp_path / f"{name}.bin"
    inp.write_bytes(data)
    return Dataset(name=name, input=inp, packet_bits=packet_bits, pt=10, ft=20, rt=50,
                   robustness=1, group=group)


def test_measure_counts_exact_bits(tmp_path):
    # F = 12 in a 2-byte stride: the identity mock emits all 16 stride bits per packet.
    ds = _dataset(tmp_path, "d", bytes(10), packet_bits=12, group="corpus")
    (r,) = sweep.run([_mock()], [ds], [{"robustness": 3, "ft": None}])
    assert (r.impl, r.group, r.dataset) == ("mock", "corpus", "d")
    assert (r.pt, r.ft, r.rt, r.robustness) == (10, None, 50, 3)
    assert (r.packets, r.packet_bits, r.input_bits, r.output_bits) == (5, 12, 60, 80)
    assert r.ratio == pytest.approx(0.75)
    assert r.reason is None


def _failing(tmp_path: Path):
    d = tmp_path / "adapters" / "failing"
    d.mkdir(parents=True)
    (d / "adapter.py").write_text(
        "import json, sys\n"
        "if sys.argv[1] == 'capabilities':\n"
        "    print(json.dumps({'build_profile': '-', 'limitations': '-'}))\n"
        "else:\n"
        "    sys.exit('boom')\n"
    )
    (d / "adapter.toml").write_text('interpreter = "python"\nentrypoint = "adapter.py"\n')
    (found,) = discover_adapters(tmp_path / "adapters", repo_root=tmp_path, variables={})
    return orchestrate.prepare_impls([found], explicit=True, build=False, max_packet_bits=None)[0]


def test_run_keeps_a_fixed_order_and_reports_a_failed_compress(tmp_path):
    first = _dataset(tmp_path, "first", bytes(10), packet_bits=16)
    second = _dataset(tmp_path, "second", bytes(4), packet_bits=16)
    configs = sweep.expand(robustness=[0, 1])
    results = sweep.run([_failing(tmp_path), _mock()], [first, second], configs, jobs=4)
    assert [(r.impl, r.dataset, r.robustness) for r in results] == [
        ("failing", "first", 0), ("failing", "first", 1),
        ("failing", "second", 0), ("failing", "second", 1),
        ("mock", "first", 0), ("mock", "first", 1),
        ("mock", "second", 0), ("mock", "second", 1),
    ]
    assert all(r.ratio is None and r.output_bits is None for r in results[:4])
    assert results[0].reason == "exit 1: boom"
    assert all(r.ratio == pytest.approx(1.0) and r.reason is None for r in results[4:])


def test_jobs_do_not_change_the_result(tmp_path):
    datasets = [_dataset(tmp_path, f"d{i}", bytes(2 * (i + 1)), packet_bits=16) for i in range(3)]
    configs = sweep.expand(pt=[1, 5, None])
    assert sweep.run([_mock()], datasets, configs, jobs=1) == sweep.run(
        [_mock()], datasets, configs, jobs=4
    )


def test_progress_counts_every_measurement(tmp_path):
    seen: list[tuple[str, int, int]] = []
    ds = _dataset(tmp_path, "d", bytes(4), packet_bits=16)
    sweep.run([_mock()], [ds], sweep.expand(pt=[1, 2]), on_progress=lambda *a: seen.append(a))
    assert seen[-1] == ("sweep", 2, 2)


def test_progress_is_reported_about_every_five_percent(tmp_path):
    seen: list[int] = []
    datasets = [_dataset(tmp_path, f"d{i}", bytes(2), packet_bits=16) for i in range(10)]
    sweep.run([_mock()], datasets, sweep.expand(pt=[1, 2, 3, 4]),
              on_progress=lambda _, done, total: seen.append(done))
    assert seen == [2 * k for k in range(1, 21)]
