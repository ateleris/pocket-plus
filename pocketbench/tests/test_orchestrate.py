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
    assert prepared[0].capabilities.ops == ["compress", "decompress"]


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


def test_build_info_from_manifest():
    p = orchestrate.prepare_impls([_mock()], explicit=True, build=True, max_packet_bits=None)[0]
    info = orchestrate.build_info(p, None)
    assert info.impl == "mock"
    assert info.language == "Python"
