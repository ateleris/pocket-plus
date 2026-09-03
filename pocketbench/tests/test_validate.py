"""Validation tests: pure verdict logic + orchestration against the mock adapter."""

from dataclasses import replace
from pathlib import Path

from pocketbench import adapter
from pocketbench import validate as validate_mod
from pocketbench.adapter.discovery import discover_adapters
from pocketbench.config import Dataset
from pocketbench.orchestrate import Prepared
from pocketbench.validate import cross_impl_verdict, sha256

REPO = Path(__file__).resolve().parents[1]


def _prepared_mock(reference_conformant: bool = True) -> Prepared:
    found = discover_adapters(REPO / "adapters", repo_root=REPO, variables={})
    (mock,) = [d for d in found if d.manifest.name == "mock"]
    caps = adapter.capabilities(mock)
    return Prepared(adapter=mock, capabilities=replace(caps, reference_conformant=reference_conformant))


def _dataset(tmp_path, *, expected: Path | None) -> Dataset:
    inp = tmp_path / "in.bin"
    inp.write_bytes(b"ORIGINAL-DATA!!!")
    return Dataset(name="d", input=inp, expected=expected, packet_bits=8,
                   pt=1, ft=1, rt=1, robustness=1)


def test_roundtrip_and_reference_pass_for_identity_codec(tmp_path):
    # Mock copies input -> packet, so an "expected" file equal to the input matches.
    expected = tmp_path / "exp.pkt"
    expected.write_bytes(b"ORIGINAL-DATA!!!")
    ds = _dataset(tmp_path, expected=expected)
    results = validate_mod.validate([_prepared_mock()], [ds], workdir=tmp_path / "wd")
    checks = {(r.impl, r.check): r for r in results}
    assert checks[("mock", "round-trip")].passed
    assert checks[("mock", "reference")].passed
    assert checks[("*", "cross-impl")].passed


def test_non_conformant_gets_roundtrip_only(tmp_path):
    expected = tmp_path / "exp.pkt"
    expected.write_bytes(b"DOES-NOT-MATTER")
    ds = _dataset(tmp_path, expected=expected)
    results = validate_mod.validate(
        [_prepared_mock(reference_conformant=False)], [ds], workdir=tmp_path / "wd"
    )
    checks = {(r.impl, r.check): r for r in results}
    assert checks[("mock", "round-trip")].passed
    assert ("mock", "reference") not in checks  # skipped: not reference-conformant
    assert ("*", "cross-impl") not in checks  # no conformant impl contributed a hash


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
