"""UAB/CNES conformance: run each suite vector through an adapter and compare it to the manifest.

The suite is 7,935 encoder + 16,965 decoder vectors in a self-describing ``.raw+config`` format
(embedded large_f, mask and per-packet flags) plus a ``file_list.csv`` manifest of expected size +
SHA-256. It does not fit the plain ``Dataset`` model, so it has its own path: each vector goes
through the adapter's ``conformance-compress`` / ``conformance-decompress`` verb and the output is
compared to the manifest by size and SHA-256.

Python rather than ccsds124's ``run_crossvalidation.sh``, whose CRLF handling is fragile on the
Windows filesystem and which spawns ``sha256sum`` per file.

It reports, it does not grade: counts plus the names of the vectors that failed, no verdict, so a
failing vector never affects the exit code (only a run that could not execute does).
"""

from __future__ import annotations

import hashlib
import subprocess
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from pocketbench import adapter
from pocketbench.orchestrate import Prepared

# Per phase: (input subdir, input suffix, output-name suffix, manifest subdir).
_PHASES = {
    "encoder": ("encoder_input", ".raw+config", ".124", "/encoder_output/"),
    "decoder": ("decoder_input", ".124+config", ".raw+large_f", "/decoder_output/"),
}


@dataclass
class ConformanceResult:
    impl: str
    mode: str
    total: int = 0
    passed: int = 0
    failed: int = 0
    # Names of the vectors that failed, sorted. Carried on the result (not only written to
    # the log) so callers can report on individual failures.
    failures: list[str] = field(default_factory=list)
    skipped_phases: list[str] = field(default_factory=list)
    results_file: Path | None = None


def load_manifest(path: Path) -> dict[str, tuple[int, str]]:
    """Parse file_list.csv into {manifest_path: (size, sha256)}."""
    manifest: dict[str, tuple[int, str]] = {}
    for line in path.read_text().splitlines():
        parts = line.split(",")
        if len(parts) != 3 or parts[0] == "path":
            continue
        manifest[parts[0]] = (int(parts[1]), parts[2].strip())
    return manifest


# Phase -> the contract verb that executes one vector.
_VERB = {"encoder": "conformance-compress", "decoder": "conformance-decompress"}


def run(
    p: Prepared,
    *,
    data_dir: Path,
    manifest: dict[str, tuple[int, str]],
    mode: str,
    results_file: Path | None = None,
    limit: int | None = None,
    on_progress: Callable[[str, int, int], None] | None = None,
    timeout: int = 60,
) -> ConformanceResult:
    """Run the selected phase(s) through the adapter and compare each output to the manifest."""
    requested = ("encoder", "decoder") if mode == "both" else (mode,)
    supported = {
        "encoder": p.capabilities.conformance_compress,
        "decoder": p.capabilities.conformance_decompress,
    }
    phases = [ph for ph in requested if supported[ph]]
    skipped = [ph for ph in requested if not supported[ph]]
    if not phases:
        raise RuntimeError(f"{p.name}: adapter does not support {mode} conformance")
    result = ConformanceResult(
        impl=p.name, mode=mode, skipped_phases=skipped, results_file=results_file,
    )
    failure_names: list[str] = []

    with tempfile.TemporaryDirectory() as tmp:
        out_path = Path(tmp) / "out"
        for phase in phases:
            in_subdir, in_suffix, out_suffix, csv_subdir = _PHASES[phase]
            verb = _VERB[phase]
            inputs = sorted((data_dir / in_subdir).glob(f"*{in_suffix}"))
            if limit is not None:
                inputs = inputs[:limit]
            for i, input_file in enumerate(inputs, 1):
                result.total += 1
                stem = input_file.name[: -len(in_suffix)]
                out_name = stem + out_suffix
                expected = manifest.get(csv_subdir + out_name)
                if _passes(p, verb, input_file, out_path, expected, timeout):
                    result.passed += 1
                else:
                    result.failed += 1
                    failure_names.append(out_name)
                if on_progress and i % 1000 == 0:
                    on_progress(phase, i, len(inputs))

    result.failures = sorted(failure_names)
    _write_log(result)
    return result


@dataclass(frozen=True)
class VectorRun:
    """Outcome of running one conformance vector through an adapter."""

    ok: bool             # adapter exited 0 and wrote an output file
    returncode: int      # -1 when the subprocess could not be run at all
    stderr: str
    data: bytes | None   # the produced output, None when nothing was written


def run_vector(
    p: Prepared, verb: str, input_file: Path, out_path: Path, timeout: int = 60
) -> VectorRun:
    """Run one vector through the adapter and return what it produced.

    Shared by the graded sweep (`run`) and by `explain`, so a single vector re-run
    reproduces exactly what the sweep did.
    """
    out_path.unlink(missing_ok=True)
    argv = adapter.conformance_argv(p.adapter, verb, input_file, out_path)
    try:
        proc = subprocess.run(argv, capture_output=True, timeout=timeout)
    except subprocess.SubprocessError as exc:
        return VectorRun(ok=False, returncode=-1, stderr=str(exc), data=None)
    stderr = proc.stderr.decode("utf-8", "replace")
    if proc.returncode != 0 or not out_path.exists():
        return VectorRun(ok=False, returncode=proc.returncode, stderr=stderr, data=None)
    return VectorRun(ok=True, returncode=0, stderr=stderr, data=out_path.read_bytes())


def _passes(
    p: Prepared, verb: str, input_file: Path, out_path: Path,
    expected: tuple[int, str] | None, timeout: int,
) -> bool:
    if expected is None:
        return False
    run = run_vector(p, verb, input_file, out_path, timeout)
    if not run.ok or run.data is None:
        return False
    return (
        len(run.data) == expected[0]
        and hashlib.sha256(run.data).hexdigest() == expected[1]
    )


def _write_log(result: ConformanceResult) -> None:
    """Write the counts plus one failing vector name per line."""
    if result.results_file is None:
        return
    result.results_file.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        f"# Conformance: {result.impl} ({result.mode})",
        f"# {result.passed} passed, {result.failed} failed of {result.total}",
        "",
        *result.failures,
    ]
    result.results_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
