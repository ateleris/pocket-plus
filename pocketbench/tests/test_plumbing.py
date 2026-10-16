"""The shared plumbing, held to one contract across its copies (Python, C, Rust).

Each copy fronts the same identity test codec, so any behavioural difference between them is a
plumbing difference.
"""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

import pytest

from pocketbench import adapter, records
from pocketbench.adapter.discovery import DiscoveredAdapter, discover_adapters
from pocketbench.records import Container, Record
from tests.adapter_contract import assert_rejects_contract_violations

REPO = Path(__file__).resolve().parents[1]
NAMES = ["mock", "c-identity", "rust-identity"]
NOT_REPRESENTABLE = "input not representable by this codec's API"

_BUILT: dict[str, DiscoveredAdapter] = {}


def _adapter(name: str) -> DiscoveredAdapter:
    if name not in _BUILT:
        found = discover_adapters(REPO / "adapters", repo_root=REPO, variables={})
        found += discover_adapters(
            REPO / "tests" / "fixtures" / "adapters", repo_root=REPO, variables={}
        )
        (d,) = [a for a in found if a.manifest.name == name]
        result = adapter.build(d)
        assert result.ok, f"{name} build failed:\n{result.stderr}"
        _BUILT[name] = d
    return _BUILT[name]


each = pytest.mark.parametrize("name", NAMES)
FIELDS = (b"\x01\x02", b"\x03\x04", b"\x05\x06")


def _compress_input(flags=(0, 0, 0)) -> Container:
    return Container(16, (Record(0, 16, b"\x00\x00"), *(Record(fl, 16, f) for fl, f in zip(flags, FIELDS))))


def _run(name: str, verb: str, container: Container | bytes, tmp_path: Path, **kw):
    inp = tmp_path / "in.rec"
    inp.write_bytes(container if isinstance(container, bytes) else records.encode(container))
    return adapter.run_verb(_adapter(name), verb, inp, tmp_path / "out.rec", **kw)


@each
def test_capabilities_are_descriptive_only(name):
    d = _adapter(name)
    proc = subprocess.run(adapter.launch_argv(d, "capabilities"), cwd=d.manifest.dir,
                          capture_output=True, text=True, check=True)
    assert set(json.loads(proc.stdout)) == {"build_profile", "limitations"}


@each
def test_compress_emits_one_record_per_packet(name, tmp_path):
    run = _run(name, "compress", _compress_input(), tmp_path)
    assert run.completed
    assert run.output == Container(16, tuple(Record(0, 16, f) for f in FIELDS))
    assert run.summary == {"records_in": 4, "records_out": 3, "stopped_at": None, "error": None}


@each
def test_compress_stops_at_the_first_hook_error(name, tmp_path):
    run = _run(name, "compress", _compress_input((0, 0x80, 0)), tmp_path)
    assert run.returncode == 0 and not run.completed
    assert run.output == Container(16, (Record(0, 16, FIELDS[0]),))
    assert run.summary == {"records_in": 4, "records_out": 1, "stopped_at": 2, "error": "codec error 7"}


@each
def test_compress_hands_an_absent_header_to_the_codec(name, tmp_path):
    run = _run(name, "compress", b"", tmp_path)
    assert run.returncode == 0
    assert run.output == Container(0, ())
    assert run.summary == {"records_in": 0, "records_out": 0, "stopped_at": 0, "error": NOT_REPRESENTABLE}


@each
def test_compress_passes_a_truncated_field_with_its_real_length(name, tmp_path):
    c = Container(16, (Record(0, 16, b"\x00\x00"), Record(0, 16, b"\x01")))
    run = _run(name, "compress", c, tmp_path)
    assert run.output == Container(16, (Record(0, 8, b"\x01"),))


@each
def test_decompress_with_known_f(name, tmp_path):
    c = Container(16, (Record(0, 16, b"\xaa\xbb"), Record(1, 0, b""), Record(0, 16, b"\xcc\xdd")))
    run = _run(name, "decompress", c, tmp_path)
    assert run.completed
    assert run.output == Container(16, (
        Record(0, 16, b"\xaa\xbb"), Record(2, 0, b""), Record(0, 16, b"\xcc\xdd"),
    ))


@each
def test_decompress_with_unknown_f_reports_failed(name, tmp_path):
    run = _run(name, "decompress", Container(0, (Record(0, 16, b"\xaa\xbb"),)), tmp_path)
    assert run.output == Container(0, (Record(1, 0, b""),))


@each
def test_decompress_hands_over_what_is_present(name, tmp_path):
    run = _run(name, "decompress", Container(16, (Record(0, 100, b"\x01\x02\x03"),)), tmp_path)
    assert run.output == Container(16, (Record(0, 24, b"\x01\x02\x03"),))


@each
def test_iterations_report_raw_nanos(name, tmp_path):
    run = _run(name, "compress", _compress_input(), tmp_path, warmup=1, iterations=4)
    assert run.completed
    nanos = run.summary["nanos"]
    assert len(nanos) == 4 and all(isinstance(n, int) and n >= 0 for n in nanos)
    assert run.output == Container(16, tuple(Record(0, 16, f) for f in FIELDS))


@each
def test_rejects_contract_violations(name, tmp_path):
    assert_rejects_contract_violations(_adapter(name), tmp_path=tmp_path)


@each
def test_decompress_stops_at_the_first_hook_error(name, tmp_path):
    c = Container(16, (Record(0, 16, b"\xaa\xbb"), Record(0, 13, b"\x00\x00"), Record(0, 16, b"\xcc\xdd")))
    run = _run(name, "decompress", c, tmp_path)
    assert run.returncode == 0 and not run.completed
    assert run.output == Container(16, (Record(0, 16, b"\xaa\xbb"),))
    assert run.summary == {"records_in": 3, "records_out": 1, "stopped_at": 1, "error": "codec error -9"}


@each
def test_decompress_stop_on_the_first_record_keeps_the_trailer(name, tmp_path):
    run = _run(name, "decompress", Container(16, (Record(0, 13, b"\x00\x00"),)), tmp_path)
    assert run.output == Container(16, ())
    assert run.summary["stopped_at"] == 0
