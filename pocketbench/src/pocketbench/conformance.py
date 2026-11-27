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
A failing encoder vector's reason also names the yellow-book input rule it breaks, if any
(``encoder_rules``), and whether the codec encoded past that packet.
"""

from __future__ import annotations

import hashlib
import os
import shutil
import tempfile
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from pocketbench import adapter, encoder_rules, records
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
    # The input rule each failing encoder vector breaks, encoder_rules.NO_RULE when it breaks none.
    rules: dict[str, str] = field(default_factory=dict)
    results_file: Path | None = None

    def by_rule(self) -> list[tuple[str, int]]:
        """Encoder failure counts per broken input rule, most frequent first, then those breaking
        none; empty without encoder failures."""
        if not self.rules:
            return []
        counts = Counter(self.rules.values())
        unexplained = counts.pop(encoder_rules.NO_RULE, 0)
        return [*counts.most_common(), (encoder_rules.NO_RULE, unexplained)]


def rule_summary(result: ConformanceResult) -> str:
    """The failures split by input rule, e.g. ``3,896 R > 7, 607 nonzero padding, 0 no input rule``."""
    return ", ".join(f"{n:,} {rule}" for rule, n in result.by_rule())


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
                      csv_subdir=csv_subdir) -> tuple[str, Failure | None]:
                out_name = input_file.name[: -len(in_suffix)] + out_suffix
                workdir = Path(tempfile.mkdtemp(dir=tmp))
                try:
                    return out_name, _failure(p, phase, input_file, workdir,
                                              manifest.get(csv_subdir + out_name), timeout)
                finally:
                    shutil.rmtree(workdir, ignore_errors=True)

            for i, (out_name, failure) in enumerate(pool.map(check, inputs), 1):
                result.total += 1
                if failure is None:
                    result.passed += 1
                else:
                    result.failed += 1
                    result.reasons[out_name] = failure.reason
                    if phase == "encoder":
                        result.rules[out_name] = (
                            failure.violation.rule if failure.violation else encoder_rules.NO_RULE)
                if on_progress and i % 1000 == 0:
                    on_progress(phase, i, len(inputs))
    result.failures = sorted(result.reasons)
    _write_log(result)
    return result


@dataclass(frozen=True)
class Failure:
    reason: str
    violation: encoder_rules.Violation | None = None


def packets_out(vr: VectorRun) -> int | None:
    """How many records the adapter wrote, None when it wrote no usable output."""
    return len(vr.run.output.records) if vr.run.output is not None else None


def _failure(
    p: Prepared, phase: str, input_file: Path, workdir: Path,
    expected: tuple[int, str] | None, timeout: int,
) -> Failure | None:
    """Why the vector failed, or None when its output matches the manifest."""
    if expected is None:
        return Failure("no manifest entry")
    vr = run_vector(p, phase, input_file, workdir, timeout)
    if (
        vr.data is not None and len(vr.data) == expected[0]
        and hashlib.sha256(vr.data).hexdigest() == expected[1]
    ):
        return None
    reason = "mismatch" if vr.run.reason == "ok" else vr.run.reason
    if phase != "encoder":
        return Failure(reason)
    violation = encoder_rules.first_violation(input_file.read_bytes())
    if violation is None:
        return Failure(reason)
    did = encoder_rules.outcome(violation, packets_out(vr))
    return Failure(f"{reason}; {violation}" + (f", {did}" if did else ""), violation)


def _write_log(result: ConformanceResult) -> None:
    """Write the counts plus one failing vector per line with its reason."""
    if result.results_file is None:
        return
    result.results_file.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        f"# Conformance: {result.impl} ({result.mode})",
        f"# {result.passed} passed, {result.failed} failed of {result.total}",
        *([f"# encoder failures by input rule: {rule_summary(result)}"] if result.rules else []),
        "",
        *(f"{name}  {result.reasons[name]}" for name in result.failures),
    ]
    result.results_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
