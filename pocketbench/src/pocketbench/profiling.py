"""Memory profiling: peak resident set size and compression ratio.

Peak RSS is a whole-process property, so the adapter runs as a subprocess under ``/usr/bin/time -v``
and the kernel-reported ``Maximum resident set size`` is read back. It is near-deterministic, so the
max over a few runs is taken.

Speed is deliberately not measured here: a subprocess wall clock includes process startup. Use the
in-process bench for timing.
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

from pocketbench import adapter, records
from pocketbench.config import Dataset
from pocketbench.orchestrate import Prepared, dataset_input

TIME_BIN = "/usr/bin/time"
_MAXRSS = re.compile(r"Maximum resident set size \(kbytes\):\s*(\d+)")


@dataclass
class ProfileResult:
    impl: str
    dataset: str
    operation: str  # "compress" | "decompress"
    input_bytes: int
    output_bytes: int
    ratio: float | None  # input/output for compress; None for decompress
    peak_rss_bytes: int
    runs: int


def time_available() -> bool:
    return Path(TIME_BIN).exists()


def parse_max_rss(time_stderr: str) -> int:
    """Extract peak RSS in bytes from `/usr/bin/time -v` stderr."""
    match = _MAXRSS.search(time_stderr)
    if not match:
        raise RuntimeError("could not find 'Maximum resident set size' in time output")
    return int(match.group(1)) * 1024  # time reports KiB


def profile(
    p: Prepared,
    dataset: Dataset,
    workdir: Path,
    *,
    runs: int = 3,
    timeout: int = 300,
) -> list[ProfileResult]:
    """Profile compress and decompress of ``dataset`` with ``p`` under /usr/bin/time -v.

    Raises RuntimeError if the codec fails or `/usr/bin/time` is unavailable.
    """
    if not time_available():
        raise RuntimeError(f"{TIME_BIN} not found; required for memory profiling")

    if workdir.exists():
        shutil.rmtree(workdir)
    workdir.mkdir(parents=True)
    data, _ = dataset_input(dataset)
    inp = workdir / "in.rec"
    inp.write_bytes(data)
    input_bytes = dataset.input.stat().st_size

    packets = workdir / "packets.rec"
    c_argv = adapter.verb_argv(p.adapter, "compress", inp, packets)
    rss_c = _profile_op(c_argv, workdir, packets, runs, timeout)
    output_bytes = len(records.to_124(records.decode(packets.read_bytes())))

    fields = workdir / "fields.rec"
    d_argv = adapter.verb_argv(p.adapter, "decompress", packets, fields)
    rss_d = _profile_op(d_argv, workdir, fields, runs, timeout)
    decoded = sum(len(r.payload) for r in records.decode(fields.read_bytes()).records)

    return [
        ProfileResult(p.name, dataset.name, "compress", input_bytes, output_bytes,
                      input_bytes / output_bytes if output_bytes else None, rss_c, runs),
        ProfileResult(p.name, dataset.name, "decompress", output_bytes, decoded, None, rss_d, runs),
    ]


def _profile_op(
    argv: list[str], workdir: Path, output: Path, runs: int, timeout: int
) -> int:
    """Run ``argv`` ``runs`` times under time -v; return the max peak RSS. ``output`` must appear."""
    rss_all = [_run_timed(argv, workdir, timeout)]
    if not output.exists():
        raise RuntimeError(f"no output produced at {output} by {argv[0]}")
    for _ in range(max(0, runs - 1)):
        rss_all.append(_run_timed(argv, workdir, timeout))
    return max(rss_all)


def _run_timed(argv: list[str], workdir: Path, timeout: int) -> int:
    """Run ``argv`` under time -v; return peak RSS in bytes. A run that stopped early is an error."""
    try:
        proc = subprocess.run(
            [TIME_BIN, "-v", *argv], cwd=workdir, capture_output=True, text=True, timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        raise RuntimeError(f"{argv[0]} timed out after {timeout} s") from None
    if proc.returncode != 0:
        raise RuntimeError(f"{argv[0]} failed (exit {proc.returncode})\n{proc.stderr.strip()}")
    try:
        summary = json.loads(proc.stdout)
    except json.JSONDecodeError:
        summary = None
    if not isinstance(summary, dict):
        raise RuntimeError(f"{argv[0]} printed no summary")
    if summary.get("stopped_at") is not None:
        raise RuntimeError(f"{argv[0]} stopped: {summary['error']}")
    return parse_max_rss(proc.stderr)
