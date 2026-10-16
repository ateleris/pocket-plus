"""Tests for conformance: manifest parsing (pure logic) + mock-driven runs.

Conformance reports, it does not grade: a run carries counts, the names of the vectors that
failed and why, and never a pass/fail verdict. Every implementation runs every phase.
"""

import hashlib
from pathlib import Path

from pocketbench import adapter, conformance
from pocketbench.adapter.discovery import discover_adapters
from pocketbench.conformance import load_manifest
from pocketbench.orchestrate import Prepared
from tests.conftest import scripted_adapter

REPO = Path(__file__).resolve().parents[1]


def test_load_manifest_skips_header_and_parses_rows(tmp_path):
    f = tmp_path / "file_list.csv"
    f.write_text(
        "path,size,sha256\n"
        "/encoder_output/encoder_sequence_0000.124,28509,abc123\n"
        "/decoder_output/decoder_sequence_00000.raw+large_f,100,def456\n"
    )
    manifest = load_manifest(f)
    assert manifest["/encoder_output/encoder_sequence_0000.124"] == (28509, "abc123")
    assert manifest["/decoder_output/decoder_sequence_00000.raw+large_f"] == (100, "def456")
    assert "path" not in manifest


def _mock_prepared() -> Prepared:
    found = discover_adapters(REPO / "adapters", repo_root=REPO, variables={})
    (mock,) = [d for d in found if d.manifest.name == "mock"]
    return Prepared(adapter=mock, capabilities=adapter.capabilities(mock))


def _raw_config(fields: list[bytes], flags: list[int] | None = None, f: int = 16) -> bytes:
    flags = flags or [0] * len(fields)
    body = b"".join(bytes([fl]) + fd for fl, fd in zip(flags, fields))
    return f.to_bytes(4, "big") + bytes((f + 7) // 8) + body


def _vector(data_dir: Path, phase: str, stem: str, payload: bytes) -> None:
    sub, suffix = {"encoder": ("encoder_input", ".raw+config"),
                   "decoder": ("decoder_input", ".124+config")}[phase]
    (data_dir / sub).mkdir(parents=True, exist_ok=True)
    (data_dir / sub / f"{stem}{suffix}").write_bytes(payload)


def _entry(data: bytes) -> tuple[int, str]:
    return len(data), hashlib.sha256(data).hexdigest()


def test_run_counts_a_matching_vector_as_passed(tmp_path):
    data_dir = tmp_path / "data"
    _vector(data_dir, "encoder", "encoder_sequence_0000", _raw_config([b"HE", b"LL"]))
    manifest = {"/encoder_output/encoder_sequence_0000.124": _entry(b"HELL")}
    result = conformance.run(_mock_prepared(), data_dir=data_dir, manifest=manifest,
                             mode="encoder", results_file=tmp_path / "log.txt")
    assert (result.total, result.passed, result.failed) == (1, 1, 0)
    assert result.failures == [] and result.reasons == {}


def test_run_reports_failing_vectors_and_why(tmp_path):
    data_dir = tmp_path / "data"
    _vector(data_dir, "encoder", "encoder_sequence_0000", _raw_config([b"HE"]))
    _vector(data_dir, "encoder", "encoder_sequence_0001", _raw_config([b"AB", b"CD"], [0, 0x80]))
    _vector(data_dir, "encoder", "encoder_sequence_0002", _raw_config([b"HE"]))
    _vector(data_dir, "encoder", "encoder_sequence_0003", _raw_config([b"HE"]))
    manifest = {
        "/encoder_output/encoder_sequence_0000.124": _entry(b"HE"),
        "/encoder_output/encoder_sequence_0001.124": _entry(b"ABCD"),
        "/encoder_output/encoder_sequence_0002.124": _entry(b"XX"),
    }
    log = tmp_path / "log.txt"
    result = conformance.run(_mock_prepared(), data_dir=data_dir, manifest=manifest,
                             mode="encoder", results_file=log)

    assert (result.total, result.passed, result.failed) == (4, 1, 3)
    assert result.failures == [
        "encoder_sequence_0001.124", "encoder_sequence_0002.124", "encoder_sequence_0003.124",
    ]
    assert result.reasons == {
        "encoder_sequence_0001.124": "stopped: codec error 7",
        "encoder_sequence_0002.124": "mismatch",
        "encoder_sequence_0003.124": "no manifest entry",
    }
    assert not hasattr(result, "ok") and not hasattr(result, "strict")

    body = log.read_text()
    assert "1 passed, 3 failed of 4" in body
    assert "encoder_sequence_0001.124  stopped: codec error 7" in body


def test_every_phase_runs_without_an_opt_in(tmp_path):
    data_dir = tmp_path / "data"
    _vector(data_dir, "decoder", "decoder_sequence_00000",
            b"\x00" + (16).to_bytes(4, "big") + b"\xaa\xbb")
    # The mock cannot start with F unknown, so it reports the one element as failed.
    manifest = {"/decoder_output/decoder_sequence_00000.raw+large_f": _entry(b"\x01" + bytes(4))}
    result = conformance.run(_mock_prepared(), data_dir=data_dir, manifest=manifest, mode="decoder")
    assert (result.total, result.passed) == (1, 1)


def test_run_honors_limit(tmp_path):
    data_dir = tmp_path / "data"
    for i in range(3):
        _vector(data_dir, "encoder", f"encoder_sequence_000{i}", _raw_config([b"HE"]))
    result = conformance.run(_mock_prepared(), data_dir=data_dir, manifest={}, mode="encoder", limit=2)
    assert result.total == 2 and result.failed == 2


def test_a_crashing_codec_is_one_failed_vector_and_the_sweep_continues(tmp_path):
    data_dir = tmp_path / "data"
    for i in range(2):
        _vector(data_dir, "encoder", f"encoder_sequence_000{i}", _raw_config([b"HE"]))
    p = Prepared(adapter=scripted_adapter(tmp_path, "os.kill(os.getpid(), 9)"),
                 capabilities=adapter.Capabilities())
    manifest = {f"/encoder_output/encoder_sequence_000{i}.124": _entry(b"HE") for i in range(2)}
    result = conformance.run(p, data_dir=data_dir, manifest=manifest, mode="encoder")
    assert (result.total, result.failed) == (2, 2)
    assert set(result.reasons.values()) == {"crashed: signal 9"}


def _mixed_suite(data_dir: Path) -> dict:
    """Vectors that pass, stop, mismatch and lack a manifest entry, in that order of names."""
    cases = [([b"HE"], None, b"HE"), ([b"AB", b"CD"], [0, 0x80], b"ABCD"),
             ([b"HE"], None, b"XX"), ([b"HE"], None, None)] * 3
    manifest = {}
    for i, (fields, flags, expected) in enumerate(cases):
        _vector(data_dir, "encoder", f"encoder_sequence_{i:04d}", _raw_config(fields, flags))
        if expected is not None:
            manifest[f"/encoder_output/encoder_sequence_{i:04d}.124"] = _entry(expected)
    return manifest


def test_a_parallel_sweep_reports_exactly_what_a_serial_one_does(tmp_path):
    data_dir = tmp_path / "data"
    manifest = _mixed_suite(data_dir)
    serial = conformance.run(_mock_prepared(), data_dir=data_dir, manifest=manifest, mode="encoder",
                             results_file=tmp_path / "serial.txt", jobs=1)
    parallel = conformance.run(_mock_prepared(), data_dir=data_dir, manifest=manifest,
                               mode="encoder", results_file=tmp_path / "parallel.txt", jobs=4)
    assert (parallel.total, parallel.passed, parallel.failed) == (serial.total, serial.passed, serial.failed) == (12, 3, 9)
    assert parallel.failures == serial.failures
    assert parallel.reasons == serial.reasons
    assert (tmp_path / "parallel.txt").read_text() == (tmp_path / "serial.txt").read_text()


def test_a_crash_in_a_parallel_sweep_is_still_one_failed_vector(tmp_path):
    data_dir = tmp_path / "data"
    for i in range(6):
        _vector(data_dir, "encoder", f"encoder_sequence_000{i}", _raw_config([b"HE"]))
    p = Prepared(adapter=scripted_adapter(tmp_path, "os.kill(os.getpid(), 9)"),
                 capabilities=adapter.Capabilities())
    manifest = {f"/encoder_output/encoder_sequence_000{i}.124": _entry(b"HE") for i in range(6)}
    result = conformance.run(p, data_dir=data_dir, manifest=manifest, mode="encoder", jobs=3)
    assert (result.total, result.failed) == (6, 6)
    assert set(result.reasons.values()) == {"crashed: signal 9"}
