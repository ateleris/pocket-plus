"""Profiling tests: peak-RSS parsing + an end-to-end profile against the mock adapter."""

from pathlib import Path

import pytest

from pocketbench import adapter, profiling
from pocketbench.adapter.discovery import discover_adapters
from pocketbench.config import Dataset
from pocketbench.orchestrate import Prepared
from pocketbench.profiling import parse_max_rss

REPO = Path(__file__).resolve().parents[1]

TIME_V_STDERR = """\
	Command being timed: "./build/ccsds124 simple.bin 90 10 20 50 1"
	User time (seconds): 0.00
	System time (seconds): 0.00
	Percent of CPU this job got: 90%
	Maximum resident set size (kbytes): 3456
	Exit status: 0
"""


def test_parse_max_rss_converts_kib_to_bytes():
    assert parse_max_rss(TIME_V_STDERR) == 3456 * 1024


def test_parse_max_rss_raises_when_absent():
    with pytest.raises(RuntimeError):
        parse_max_rss("no rss line here")


def _prepared_mock() -> Prepared:
    found = discover_adapters(REPO / "adapters", repo_root=REPO, variables={})
    (mock,) = [d for d in found if d.manifest.name == "mock"]
    return Prepared(adapter=mock, capabilities=adapter.capabilities(mock))


def test_profile_mock_end_to_end(tmp_path):
    if not profiling.time_available():
        pytest.skip("/usr/bin/time not available")
    inp = tmp_path / "in.bin"
    inp.write_bytes(b"X" * 900)
    ds = Dataset(name="d", input=inp, expected=None, packet_bits=720, pt=1, ft=1, rt=1, robustness=1)
    results = profiling.profile(_prepared_mock(), ds, tmp_path / "wd", runs=2)
    ops = {r.operation: r for r in results}
    assert set(ops) == {"compress", "decompress"}
    # Identity codec: compressed output equals input, ratio ~ 1.0.
    assert ops["compress"].output_bytes == 900
    assert ops["compress"].peak_rss_bytes > 0
