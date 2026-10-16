"""UAB/CNES conformance: run each suite vector through an adapter and compare it to the manifest.

The suite is 7,935 encoder + 16,965 decoder vectors (``.raw+config`` / ``.124+config``) plus a
``file_list.csv`` manifest of expected size + SHA-256. Each vector is re-framed into the record
container (``records.py``, which copies bytes and judges nothing), run through the same
``compress`` / ``decompress`` verb every other command uses, converted back to the suite's format
and compared by size and SHA-256. Every implementation runs every vector: whatever its codec does
with a malformed input is the result.

Python rather than ccsds124's ``run_crossvalidation.sh``, whose CRLF handling is fragile on the
Windows filesystem and which spawns ``sha256sum`` per file.

It reports, it does not grade: counts, the failing vector names and why each failed, and no
verdict, so a failing vector never affects the exit code (only a run that could not execute does).
"""

from __future__ import annotations

import hashlib
import os
import shutil
import tempfile
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from pocketbench import adapter, records
from pocketbench.adapter import VerbRun
from pocketbench.orchestrate import Prepared

# Per phase: (input subdir, input suffix, output-name suffix, manifest subdir).
_PHASES = {
    "encoder": ("encoder_input", ".raw+config", ".124", "/encoder_output/"),
    "decoder": ("decoder_input", ".124+config", ".raw+large_f", "/decoder_output/"),
}

# Per phase: (re-frame the suite input, convert the output back, the verb that runs it).
_CODEC_PATH = {
    "encoder": (records.from_raw_config, records.to_124, "compress"),
    "decoder": (records.from_124_config, records.to_raw_large_f, "decompress"),
}


@dataclass
class ConformanceResult:
    impl: str
    mode: str
    total: int = 0
    passed: int = 0
    failed: int = 0
    # Names of the vectors that failed, sorted.
    failures: list[str] = field(default_factory=list)
    # Why each failing vector failed: mismatch, stopped: <error>, crashed: signal N, timeout, ...
    reasons: dict[str, str] = field(default_factory=dict)
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


@dataclass(frozen=True)
class VectorRun:
    """Outcome of running one suite vector through an adapter."""

    run: VerbRun
    data: bytes | None  # the output in the suite's format; None when nothing usable was written


def run_vector(
    p: Prepared, phase: str, input_file: Path, workdir: Path, timeout: int = 60
) -> VectorRun:
    """Run one vector through the adapter.

    Shared by the sweep (``run``) and by ``explain``, so a single-vector re-run reproduces exactly
    what the sweep did.
    """
    reframe, convert, verb = _CODEC_PATH[phase]
    inp, out = workdir / "vector.rec", workdir / "output.rec"
    inp.write_bytes(records.encode(reframe(input_file.read_bytes())))
    run = adapter.run_verb(p.adapter, verb, inp, out, timeout=timeout)
    return VectorRun(run, convert(run.output) if run.output is not None else None)


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
    jobs: int | None = None,
) -> ConformanceResult:
    """Run the selected phase(s) through the adapter and compare each output to the manifest.

    Vectors run ``jobs`` at a time (default: one per CPU), each in its own adapter process and
    working directory, so the result does not depend on ``jobs``.
    """
    phases = ("encoder", "decoder") if mode == "both" else (mode,)
    result = ConformanceResult(impl=p.name, mode=mode, results_file=results_file)
    with tempfile.TemporaryDirectory() as tmp, \
            ThreadPoolExecutor(max_workers=jobs or os.cpu_count() or 1) as pool:
        for phase in phases:
            in_subdir, in_suffix, out_suffix, csv_subdir = _PHASES[phase]
            inputs = sorted((data_dir / in_subdir).glob(f"*{in_suffix}"))
            if limit is not None:
                inputs = inputs[:limit]

            def check(input_file: Path, phase=phase, in_suffix=in_suffix, out_suffix=out_suffix,
                      csv_subdir=csv_subdir) -> tuple[str, str | None]:
                out_name = input_file.name[: -len(in_suffix)] + out_suffix
                workdir = Path(tempfile.mkdtemp(dir=tmp))
                try:
                    return out_name, _failure(p, phase, input_file, workdir,
                                              manifest.get(csv_subdir + out_name), timeout)
                finally:
                    shutil.rmtree(workdir, ignore_errors=True)

            for i, (out_name, reason) in enumerate(pool.map(check, inputs), 1):
                result.total += 1
                if reason is None:
                    result.passed += 1
                else:
                    result.failed += 1
                    result.reasons[out_name] = reason
                if on_progress and i % 1000 == 0:
                    on_progress(phase, i, len(inputs))
    result.failures = sorted(result.reasons)
    _write_log(result)
    return result


def _failure(
    p: Prepared, phase: str, input_file: Path, workdir: Path,
    expected: tuple[int, str] | None, timeout: int,
) -> str | None:
    """Why the vector failed, or None when its output matches the manifest."""
    if expected is None:
        return "no manifest entry"
    vr = run_vector(p, phase, input_file, workdir, timeout)
    if (
        vr.data is not None and len(vr.data) == expected[0]
        and hashlib.sha256(vr.data).hexdigest() == expected[1]
    ):
        return None
    reason = vr.run.reason
    return "mismatch" if reason == "ok" else reason


def _write_log(result: ConformanceResult) -> None:
    """Write the counts plus one failing vector per line with its reason."""
    if result.results_file is None:
        return
    result.results_file.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        f"# Conformance: {result.impl} ({result.mode})",
        f"# {result.passed} passed, {result.failed} failed of {result.total}",
        "",
        *(f"{name}  {result.reasons[name]}" for name in result.failures),
    ]
    result.results_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
