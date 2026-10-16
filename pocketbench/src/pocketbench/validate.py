"""Correctness validation for CCSDS 124.0-B-1 implementations.

Three checks, all off the same ``compress`` / ``decompress`` verbs every command uses:

1. round-trip  - decompress(compress(x)) decodes every packet and reproduces x.
2. reference   - the compressed packets are byte-identical to the ``expected-output/*.pkt``
                 shipped with the test vectors.
3. cross-impl  - every selected impl emits byte-identical packets for the same dataset (by
                 SHA-256), equal to the reference packet's hash when one is present.

Every implementation goes through every check.
"""

from __future__ import annotations

import hashlib
import shutil
from dataclasses import dataclass
from pathlib import Path

from pocketbench import adapter, records
from pocketbench.config import Dataset
from pocketbench.orchestrate import Prepared, dataset_input


@dataclass
class ValidationResult:
    impl: str  # "*" for cross-impl checks
    dataset: str
    check: str  # "compress" | "round-trip" | "reference" | "cross-impl"
    passed: bool
    detail: str = ""


def sha256(path: Path) -> str:
    return _digest(path.read_bytes())


def _digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def cross_impl_verdict(
    hashes: dict[str, str], expected_hash: str | None
) -> tuple[bool, str]:
    """Pure check: are all impl packet hashes equal (and equal to reference)?

    ``hashes`` maps impl name -> SHA-256 of its compressed packets.
    """
    distinct = set(hashes.values())
    if expected_hash is not None:
        mismatched = sorted(n for n, h in hashes.items() if h != expected_hash)
        if mismatched:
            return False, f"differ from reference: {', '.join(mismatched)}"
        return True, f"{len(hashes)} impls match reference {expected_hash[:12]}"
    if len(distinct) > 1:
        groups = sorted(f"{n}={h[:8]}" for n, h in hashes.items())
        return False, f"packets not identical: {', '.join(groups)}"
    return True, f"{len(hashes)} impls identical ({next(iter(distinct))[:12]})"


def validate(
    prepared: list[Prepared], datasets: list[Dataset], *, workdir: Path
) -> list[ValidationResult]:
    """Run compress/round-trip/reference per (impl, dataset) and cross-impl."""
    results: list[ValidationResult] = []
    for dataset in datasets:
        data, _ = dataset_input(dataset)
        packet_hashes: dict[str, str] = {}
        for p in prepared:
            run_dir = workdir / p.name / dataset.name
            if run_dir.exists():
                shutil.rmtree(run_dir)
            run_dir.mkdir(parents=True)
            (run_dir / "in.rec").write_bytes(data)
            comp = adapter.run_verb(p.adapter, "compress", run_dir / "in.rec", run_dir / "packets.rec")
            if not comp.completed:
                results.append(ValidationResult(p.name, dataset.name, "compress", False, comp.reason))
                continue
            packets = records.to_124(comp.output)
            packet_hashes[p.name] = _digest(packets)
            if dataset.expected is not None:
                results.append(_reference_check(p.name, dataset, packets))
            results.append(_round_trip_check(p, dataset, run_dir))
        if packet_hashes:
            results.append(_cross_impl_check(dataset, packet_hashes))
    return results


def _reference_check(name: str, dataset: Dataset, packets: bytes) -> ValidationResult:
    expected = dataset.expected
    if not expected.exists():
        return ValidationResult(name, dataset.name, "reference", False,
                                f"reference missing: {expected}")
    reference = expected.read_bytes()
    passed = packets == reference
    detail = "" if passed else f"{len(packets)} B vs expected {len(reference)} B"
    return ValidationResult(name, dataset.name, "reference", passed, detail)


def _round_trip_check(p: Prepared, dataset: Dataset, run_dir: Path) -> ValidationResult:
    dec = adapter.run_verb(p.adapter, "decompress", run_dir / "packets.rec", run_dir / "fields.rec")
    if not dec.completed:
        return ValidationResult(p.name, dataset.name, "round-trip", False, dec.reason)
    failed = sum(r.kind != records.STATUS_DECODED for r in dec.output.records)
    decoded = b"".join(r.payload for r in dec.output.records)
    original = dataset.input.read_bytes()
    if failed:
        detail = f"{failed} of {len(dec.output.records)} packets not decoded"
    elif decoded != original:
        detail = f"{len(decoded)} B vs original {len(original)} B"
    else:
        detail = ""
    return ValidationResult(p.name, dataset.name, "round-trip", not detail, detail)


def _cross_impl_check(dataset: Dataset, packet_hashes: dict[str, str]) -> ValidationResult:
    expected_hash = sha256(dataset.expected) if dataset.expected and dataset.expected.exists() else None
    passed, detail = cross_impl_verdict(packet_hashes, expected_hash)
    return ValidationResult("*", dataset.name, "cross-impl", passed, detail)
