"""Correctness validation for CCSDS 124.0-B-1 implementations.

Three checks:

1. round-trip  - decompress(compress(x)) == x.
2. reference   - compressed output is byte-identical to the ``expected-output/*.pkt`` shipped
                 with the test vectors.
3. cross-impl  - every selected impl emits byte-identical packets for the same dataset (by
                 SHA-256), equal to the reference packet's hash when one is present.

:func:`validate` compresses each (impl, dataset) once in an isolated working directory and derives
all three from that.
"""

from __future__ import annotations

import hashlib
import shutil
from dataclasses import dataclass
from pathlib import Path

from pocketbench import adapter
from pocketbench.adapter import Params
from pocketbench.config import Dataset
from pocketbench.orchestrate import Prepared


@dataclass
class ValidationResult:
    impl: str  # "*" for cross-impl checks
    dataset: str
    check: str  # "compress" | "round-trip" | "reference" | "cross-impl"
    passed: bool
    detail: str = ""


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def cross_impl_verdict(
    hashes: dict[str, str], expected_hash: str | None
) -> tuple[bool, str]:
    """Pure check: are all impl packet hashes equal (and equal to reference)?

    ``hashes`` maps impl name -> SHA-256 of its compressed packet.
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
        packet_hashes: dict[str, str] = {}
        for p in prepared:
            name = p.name
            run_dir = workdir / name / dataset.name
            if run_dir.exists():
                shutil.rmtree(run_dir)
            run_dir.mkdir(parents=True, exist_ok=True)
            params = Params(
                packet_bits=dataset.packet_bits, pt=dataset.pt, ft=dataset.ft,
                rt=dataset.rt, robustness=dataset.robustness,
            )
            packet = run_dir / "out.pkt"
            comp = adapter.compress(p.adapter, params, dataset.input, packet)
            if not comp.ok:
                results.append(
                    ValidationResult(name, dataset.name, "compress", False,
                                     comp.stderr.strip() or f"exit {comp.returncode}")
                )
                continue
            # A non-conformant impl (e.g. a different flag schedule) gets round-trip only, so
            # its expected divergence from the ESA reference is not reported as a failure.
            if p.capabilities.reference_conformant:
                packet_hashes[name] = sha256(packet)
                if dataset.expected is not None:
                    results.append(_reference_check(name, dataset, packet))
            results.append(_round_trip_check(p, dataset, packet, params, run_dir))
        if packet_hashes:
            results.append(_cross_impl_check(dataset, packet_hashes))
    return results


def _reference_check(name: str, dataset: Dataset, packet: Path) -> ValidationResult:
    expected = dataset.expected
    if not expected.exists():
        return ValidationResult(name, dataset.name, "reference", False,
                                f"reference missing: {expected}")
    passed = sha256(packet) == sha256(expected)
    detail = "" if passed else f"{packet.stat().st_size} B vs expected {expected.stat().st_size} B"
    return ValidationResult(name, dataset.name, "reference", passed, detail)


def _round_trip_check(
    p: Prepared, dataset: Dataset, packet: Path, params: Params, run_dir: Path
) -> ValidationResult:
    depkt = run_dir / "out.depkt"
    dec = adapter.decompress(p.adapter, params, packet, depkt)
    if not dec.ok:
        return ValidationResult(p.name, dataset.name, "round-trip", False,
                                dec.stderr.strip() or f"exit {dec.returncode}")
    passed = sha256(depkt) == sha256(dataset.input)
    detail = "" if passed else (
        f"{depkt.stat().st_size} B vs original {dataset.input.stat().st_size} B"
    )
    return ValidationResult(p.name, dataset.name, "round-trip", passed, detail)


def _cross_impl_check(dataset: Dataset, packet_hashes: dict[str, str]) -> ValidationResult:
    if not packet_hashes:
        return ValidationResult("*", dataset.name, "cross-impl", False,
                                "no successful compressions")
    expected_hash = sha256(dataset.expected) if dataset.expected and dataset.expected.exists() else None
    passed, detail = cross_impl_verdict(packet_hashes, expected_hash)
    return ValidationResult("*", dataset.name, "cross-impl", passed, detail)
