"""Tests for conformance: manifest parsing (pure logic) + mock-driven runs.

Conformance reports, it does not grade: a run carries counts and the names of the vectors
that failed, and never a pass/fail verdict.
"""

import hashlib
from pathlib import Path

from pocketbench import adapter, conformance
from pocketbench.adapter.discovery import discover_adapters
from pocketbench.conformance import load_manifest
from pocketbench.orchestrate import Prepared

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


def _mock_prepared():
    found = discover_adapters(REPO / "adapters", repo_root=REPO, variables={})
    (mock,) = [d for d in found if d.manifest.name == "mock"]
    return Prepared(adapter=mock, capabilities=adapter.capabilities(mock))


def _encoder_vector(data_dir: Path, stem: str, payload: bytes) -> None:
    (data_dir / "encoder_input").mkdir(parents=True, exist_ok=True)
    (data_dir / "encoder_input" / f"{stem}.raw+config").write_bytes(payload)


def test_run_counts_a_matching_vector_as_passed(tmp_path):
    data_dir = tmp_path / "data"
    _encoder_vector(data_dir, "encoder_sequence_0000", b"HELLO")
    # Mock echoes input, so the manifest entry for the echoed output must match b"HELLO".
    manifest = {
        "/encoder_output/encoder_sequence_0000.124": (5, hashlib.sha256(b"HELLO").hexdigest())
    }
    result = conformance.run(
        _mock_prepared(), data_dir=data_dir, manifest=manifest,
        mode="encoder", results_file=tmp_path / "log.txt",
    )
    assert (result.total, result.passed, result.failed) == (1, 1, 0)
    assert result.failures == []


def test_run_reports_failing_vectors_without_a_verdict(tmp_path):
    data_dir = tmp_path / "data"
    _encoder_vector(data_dir, "encoder_sequence_0000", b"HELLO")   # matches
    _encoder_vector(data_dir, "encoder_sequence_0001", b"NOPE")    # digest mismatch
    _encoder_vector(data_dir, "encoder_sequence_0002", b"HELLO")   # no manifest entry
    manifest = {
        "/encoder_output/encoder_sequence_0000.124": (5, hashlib.sha256(b"HELLO").hexdigest()),
        "/encoder_output/encoder_sequence_0001.124": (4, hashlib.sha256(b"HELO").hexdigest()),
    }
    log = tmp_path / "log.txt"
    result = conformance.run(
        _mock_prepared(), data_dir=data_dir, manifest=manifest,
        mode="encoder", results_file=log,
    )

    assert (result.total, result.passed, result.failed) == (3, 1, 2)
    assert result.failures == [
        "encoder_sequence_0001.124",
        "encoder_sequence_0002.124",
    ]
    # No verdict is rendered anywhere on the result.
    assert not hasattr(result, "ok") and not hasattr(result, "strict")

    body = log.read_text()
    assert "1 passed, 2 failed of 3" in body
    assert "encoder_sequence_0001.124" in body
    # The log records counts and names only: no verdict, and no baseline classification.
    assert "known" not in body and "NEW" not in body


def test_run_honors_limit(tmp_path):
    data_dir = tmp_path / "data"
    for i in range(3):
        _encoder_vector(data_dir, f"encoder_sequence_000{i}", b"HELLO")
    result = conformance.run(
        _mock_prepared(), data_dir=data_dir, manifest={}, mode="encoder", limit=2
    )
    assert result.total == 2 and result.failed == 2
