"""Validation tests: pure verdict logic + orchestration against the mock adapter."""

from pathlib import Path

from pocketbench import adapter
from pocketbench import validate as validate_mod
from pocketbench.adapter.discovery import discover_adapters
from pocketbench.config import Dataset
from pocketbench.orchestrate import Prepared
from pocketbench.validate import cross_impl_verdict, sha256

REPO = Path(__file__).resolve().parents[1]
ORIGINAL = b"ORIGINAL-DATA!!!"


def _prepared_mock() -> Prepared:
    found = discover_adapters(REPO / "adapters", repo_root=REPO, variables={})
    (mock,) = [d for d in found if d.manifest.name == "mock"]
    return Prepared(adapter=mock, capabilities=adapter.capabilities(mock))


def _dataset(tmp_path, *, expected: bytes | None) -> Dataset:
    inp = tmp_path / "in.bin"
    inp.write_bytes(ORIGINAL)
    exp = None
    if expected is not None:
        exp = tmp_path / "exp.pkt"
        exp.write_bytes(expected)
    return Dataset(name="d", input=inp, expected=exp, packet_bits=8, pt=1, ft=1, rt=1, robustness=1)


def _checks(tmp_path, expected):
    results = validate_mod.validate([_prepared_mock()], [_dataset(tmp_path, expected=expected)],
                                    workdir=tmp_path / "wd")
    return {(r.impl, r.check): r for r in results}


def test_identity_codec_passes_every_check_when_expected_equals_input(tmp_path):
    # The identity codec's packets are its fields, so an expected .pkt equal to the input matches.
    checks = _checks(tmp_path, ORIGINAL)
    assert checks[("mock", "round-trip")].passed
    assert checks[("mock", "reference")].passed
    assert checks[("*", "cross-impl")].passed


def test_every_impl_gets_the_reference_check(tmp_path):
    checks = _checks(tmp_path, b"SOMETHING-ELSE!!")
    assert checks[("mock", "round-trip")].passed
    assert not checks[("mock", "reference")].passed
    assert not checks[("*", "cross-impl")].passed


def test_sha256_of_file(tmp_path):
    f = tmp_path / "x.bin"
    f.write_bytes(b"hello")
    assert sha256(f) == "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"


def test_verdict_all_identical_no_reference():
    passed, detail = cross_impl_verdict({"c": "aa", "rust": "aa"}, None)
    assert passed and "identical" in detail


def test_verdict_divergent_no_reference():
    passed, detail = cross_impl_verdict({"c": "aa", "rust": "bb"}, None)
    assert not passed and "not identical" in detail


def test_verdict_matches_reference():
    passed, detail = cross_impl_verdict({"c": "aa", "rust": "aa"}, "aa")
    assert passed and "match reference" in detail


def test_verdict_diverges_from_reference():
    passed, detail = cross_impl_verdict({"c": "aa", "rust": "bb"}, "aa")
    assert not passed and "rust" in detail and "differ from reference" in detail


def test_verdict_single_impl_identical_trivially():
    passed, _ = cross_impl_verdict({"c": "aa"}, None)
    assert passed
