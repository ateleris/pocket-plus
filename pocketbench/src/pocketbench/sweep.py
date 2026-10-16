"""Compression ratio over pt / ft / rt / robustness configurations (the ``sweep`` command).

Each measurement runs the ``compress`` verb once on one dataset with its parameters replaced by one
configuration, and counts exact bits: packets x F in, the sum of the compressed packets' bits out.
Nothing is timed, so measurements run in parallel, each in its own adapter process.
"""

from __future__ import annotations

import itertools
import os
import shutil
import tempfile
from collections.abc import Callable, Iterable
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path

from pocketbench import adapter
from pocketbench.config import Dataset
from pocketbench.orchestrate import Prepared, dataset_input

PARAMS = ("pt", "ft", "rt", "robustness")

Configuration = dict[str, int | None]


@dataclass(frozen=True)
class SweepResult:
    impl: str
    group: str
    dataset: str
    pt: int | None
    ft: int | None
    rt: int | None
    robustness: int
    packets: int
    packet_bits: int
    input_bits: int
    output_bits: int | None  # None when the compress did not complete
    ratio: float | None
    reason: str | None       # why the compress did not complete; None when it did


def parse_values(spec: str, *, allow_off: bool) -> list[int | None]:
    """``"off,5,10-12"`` -> ``[None, 5, 10, 11, 12]``: integers, inclusive ranges and ``off``."""
    values: list[int | None] = []
    for item in (part.strip() for part in spec.split(",")):
        if item == "off":
            if not allow_off:
                raise ValueError(f"'off' is not a value for this parameter: {spec!r}")
            values.append(None)
        elif "-" in item:
            low, _, high = item.partition("-")
            if not (low.isdigit() and high.isdigit()) or int(low) > int(high):
                raise ValueError(f"not a range a-b with a <= b: {item!r} in {spec!r}")
            values.extend(range(int(low), int(high) + 1))
        elif item.isdigit():
            values.append(int(item))
        else:
            raise ValueError(f"not an integer, a range or 'off': {item!r} in {spec!r}")
    return values


def expand(
    *,
    pt: Iterable[int | None] | None = None,
    ft: Iterable[int | None] | None = None,
    rt: Iterable[int | None] | None = None,
    robustness: Iterable[int] | None = None,
    configs: Iterable[Configuration] | None = None,
) -> list[Configuration]:
    """Every combination of the given value lists (in PARAMS order), or ``configs`` as given."""
    lists = {k: v for k, v in zip(PARAMS, (pt, ft, rt, robustness)) if v is not None}
    if configs is not None:
        if lists:
            raise ValueError("give either value lists or configs, not both")
        configs = [dict(c) for c in configs]
        for c in configs:
            unknown = sorted(set(c) - set(PARAMS))
            if unknown:
                raise ValueError(f"unknown parameters {unknown}; known: {', '.join(PARAMS)}")
        return configs
    values = {k: list(v) for k, v in lists.items()}
    empty = [k for k, v in values.items() if not v]
    if empty:
        raise ValueError(f"no values for {', '.join(empty)}")
    return [dict(zip(values, combo)) for combo in itertools.product(*values.values())]


def measure(p: Prepared, dataset: Dataset, config: Configuration, *, timeout: int) -> SweepResult:
    """Compress ``dataset`` once under ``config`` and count the bits."""
    ds = dataset.model_copy(update=config)
    data, packets = dataset_input(ds)
    workdir = Path(tempfile.mkdtemp(prefix="pocketbench-sweep-"))
    try:
        (workdir / "in.rec").write_bytes(data)
        run = adapter.run_verb(p.adapter, "compress", workdir / "in.rec", workdir / "out.rec",
                               timeout=timeout)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)
    input_bits = packets * ds.packet_bits
    output_bits = sum(r.bits for r in run.output.records) if run.completed else None
    return SweepResult(
        impl=p.name, group=ds.group, dataset=ds.name,
        pt=ds.pt, ft=ds.ft, rt=ds.rt, robustness=ds.robustness,
        packets=packets, packet_bits=ds.packet_bits,
        input_bits=input_bits,
        output_bits=output_bits,
        ratio=input_bits / output_bits if output_bits else None,
        reason=None if run.completed else run.reason,
    )


def run(
    prepared: list[Prepared],
    datasets: list[Dataset],
    configs: list[Configuration],
    *,
    jobs: int | None = None,
    timeout: int = 600,
    on_progress: Callable[[str, int, int], None] | None = None,
) -> list[SweepResult]:
    """Measure every impl x dataset x configuration, ``jobs`` at a time, in that fixed order."""
    tasks = [(p, ds, c) for p in prepared for ds in datasets for c in configs]
    results: list[SweepResult] = []
    step = max(1, len(tasks) // 20)
    with ThreadPoolExecutor(max_workers=jobs or os.cpu_count() or 1) as pool:
        for i, result in enumerate(
            pool.map(lambda t: measure(*t, timeout=timeout), tasks), 1
        ):
            results.append(result)
            if on_progress and (i % step == 0 or i == len(tasks)):
                on_progress("sweep", i, len(tasks))
    return results
