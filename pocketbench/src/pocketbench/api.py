"""Importable programmatic facade for pocketbench.

``PocketBench`` is the single source of truth for all five operations
(``build`` / ``validate`` / ``bench`` / ``profile`` / ``conformance``). Both a Jupyter notebook
and the ``pocket-bench`` CLI drive the same class, so their behavior can never diverge: the CLI
(``cli.py``) is a thin typer layer that constructs a ``PocketBench``, calls its methods, renders the
returned containers to the console, and maps outcomes to exit codes.

It never imports ``typer`` and never prints: progress goes through optional ``on_progress`` /
``on_message`` callbacks (silent by default), setup errors raise :class:`PocketBenchError`
subclasses, and an expected outcome (a validation FAIL) is returned as data on the container's
``ok``. ``conformance`` renders no verdict: :class:`ConformanceRun` carries counts, failing vector
names and their reasons.

Compute methods write nothing to disk; persistence is the opt-in :meth:`PocketBench.write_report`.
Validate/profile scratch files go to an auto-cleaned temp dir.
"""

from __future__ import annotations

import tempfile
from collections.abc import Callable, Sequence
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import TYPE_CHECKING

from pocketbench import adapter, conformance, explain as explain_mod, orchestrate, profiling, report
from pocketbench import sweep as sweep_mod
from pocketbench import validate as validate_mod
from pocketbench.benchmark import BenchMetrics, BuildInfo
from pocketbench.config import Config, Dataset, load_config
from pocketbench.conformance import ConformanceResult
from pocketbench.explain import ExplainResult
from pocketbench.orchestrate import Prepared
from pocketbench.profiling import ProfileResult
from pocketbench.sweep import SweepResult
from pocketbench.validate import ValidationResult

if TYPE_CHECKING:  # pragma: no cover - typing only
    import pandas as pd

ProgressCB = Callable[[str, int, int], None]
MessageCB = Callable[[str], None]


# --------------------------------------------------------------------------- errors


class PocketBenchError(RuntimeError):
    """Base for setup/usage errors the facade raises (the CLI maps these to exit codes)."""


class ConfigError(PocketBenchError):
    """The config path is missing or cannot be loaded."""


class SelectionError(PocketBenchError):
    """An unknown implementation or dataset name was requested."""


class ProfileUnavailable(PocketBenchError):
    """Memory profiling cannot run (``/usr/bin/time`` is not present)."""


class ConformanceUnavailable(PocketBenchError):
    """Conformance cannot run (no suite discovered, data missing, or bad mode)."""


class VectorNotFound(PocketBenchError):
    """A named conformance vector does not exist in the suite."""


def _pandas():
    try:
        import pandas as pd
    except ImportError as exc:  # pragma: no cover - exercised via monkeypatch only
        raise ImportError(
            "pandas is required for .to_dataframe(); install it with "
            "`uv sync --extra notebook` (or `pip install pandas`)."
        ) from exc
    return pd


# --------------------------------------------------------------------------- containers


@dataclass
class BuildOutcome:
    """One implementation's build result."""

    impl: str
    ok: bool
    stderr: str


@dataclass
class BuildRun:
    """Result of :meth:`PocketBench.build`."""

    results: list[BuildOutcome]

    @property
    def ok(self) -> bool:
        return all(r.ok for r in self.results)

    def to_dataframe(self) -> "pd.DataFrame":
        return _pandas().DataFrame([asdict(r) for r in self.results])


@dataclass
class ValidationRun:
    """Result of :meth:`PocketBench.validate`."""

    results: list[ValidationResult]

    @property
    def ok(self) -> bool:
        return all(r.passed for r in self.results)

    def to_dataframe(self) -> "pd.DataFrame":
        return _pandas().DataFrame([asdict(r) for r in self.results])


@dataclass
class BenchRun:
    """Result of :meth:`PocketBench.bench` (plus what the report writers need)."""

    metrics: list[BenchMetrics]
    build_infos: list[BuildInfo]
    environment: dict
    iterations: int  # the fixed count, or the cap under a time budget
    time_budget: float | None = None

    def to_dataframe(self) -> "pd.DataFrame":
        return _pandas().DataFrame([asdict(m) for m in self.metrics])


@dataclass
class ProfileRun:
    """Result of :meth:`PocketBench.profile`."""

    results: list[ProfileResult]
    environment: dict
    runs: int

    def to_dataframe(self) -> "pd.DataFrame":
        return _pandas().DataFrame([asdict(r) for r in self.results])


@dataclass
class ConformanceRun:
    """Result of :meth:`PocketBench.conformance`."""

    results: list[ConformanceResult]
    mode: str

    def to_dataframe(self) -> "pd.DataFrame":
        rows = [
            {
                "impl": r.impl,
                "mode": r.mode,
                "total": r.total,
                "passed": r.passed,
                "failed": r.failed,
            }
            for r in self.results
        ]
        return _pandas().DataFrame(rows)


@dataclass
class SweepRun:
    """Result of :meth:`PocketBench.sweep`. No ``.ok``: a sweep reports, it does not grade."""

    results: list[SweepResult]
    parameters: dict  # the value lists or {"configs": [...]}, as passed
    environment: dict

    def to_dataframe(self) -> "pd.DataFrame":
        return _pandas().DataFrame([asdict(r) for r in self.results])


@dataclass
class ExplainRun:
    """Result of :meth:`PocketBench.explain`: one entry per (impl, vector)."""

    results: list[ExplainResult]

    def __iter__(self):
        return iter(self.results)

    def __getitem__(self, i: int) -> ExplainResult:
        return self.results[i]

    def summary(self) -> str:
        """The same text the CLI prints, for notebook use."""
        return "\n".join(
            line for r in self.results for line in (*explain_mod.summary_lines(r), "")
        ).rstrip()

    def to_dataframe(self) -> "pd.DataFrame":
        """One row per diverging frame (decoder), so a notebook can filter across vectors."""
        rows = []
        for r in self.results:
            if r.decoder is None:
                continue
            for fd in r.decoder.frame_diffs:
                rows.append({
                    "impl": r.impl,
                    "vector": r.vector,
                    "frame": fd.index,
                    "expected_status": fd.expected_status,
                    "actual_status": fd.actual_status,
                    "first_diff_bit": fd.first_diff_bit,
                    "last_diff_bit": fd.last_diff_bit,
                })
        return _pandas().DataFrame(rows)


# --------------------------------------------------------------------------- facade


def _noop_message(_msg: str) -> None:
    pass


def _noop_progress(_phase: str, _done: int, _total: int) -> None:
    pass


class PocketBench:
    """Run every pocketbench operation programmatically and get structured results back."""

    def __init__(
        self,
        config: str | Path | Config,
        *,
        on_progress: ProgressCB | None = None,
        on_message: MessageCB | None = None,
    ) -> None:
        if isinstance(config, Config):
            self.config = config
        else:
            path = Path(config)
            if not path.exists():
                example = path.with_name(path.stem + ".example" + path.suffix)
                hint = f"; copy {example} to {path} and edit it" if example.exists() else ""
                raise ConfigError(f"config not found: {path}{hint}")
            try:
                self.config = load_config(path)
            except Exception as exc:  # noqa: BLE001 - surface any load failure uniformly
                raise ConfigError(f"could not load config {path}: {exc}") from exc
        self._on_progress = on_progress
        self._on_message = on_message

    # -- helpers -----------------------------------------------------------

    def _message_cb(self, override: MessageCB | None) -> MessageCB:
        return override or self._on_message or _noop_message

    def _progress_cb(self, override: ProgressCB | None) -> ProgressCB:
        return override or self._on_progress or _noop_progress

    def _select_impls(self, names: list[str] | None, command: str):
        try:
            return self.config.select_impls(names, command=command)
        except KeyError as exc:
            raise SelectionError(exc.args[0]) from None

    def _select_datasets(self, names: list[str] | None):
        try:
            return self.config.select_datasets(names)
        except KeyError as exc:
            raise SelectionError(exc.args[0]) from None

    @staticmethod
    def _build_size(dataset: Dataset) -> int:
        """The F a size-specialized build is compiled for to run `dataset`.

        Floored at 720 (90-byte packets) so an F=1 dataset still builds the reference-c codec with
        a sane buffer size rather than a degenerate 1-bit one.
        """
        return max(dataset.packet_bits, 720)

    def _max_packet_bits(self) -> int:
        """The size for a standalone `build`, which has no dataset selection: all datasets."""
        return max((self._build_size(d) for d in self.config.datasets.values()), default=720)

    def _prepare(
        self, names: list[str] | None, *, command: str, build: bool, max_packet_bits: int | None,
        on_message: MessageCB,
    ) -> list[Prepared]:
        return orchestrate.prepare_impls(
            self._select_impls(names, command),
            explicit=names is not None,
            build=build,
            max_packet_bits=max_packet_bits,
            on_message=on_message,
        )

    # -- operations --------------------------------------------------------

    def build(self, impls: list[str] | None = None, *, on_message: MessageCB | None = None) -> BuildRun:
        """Build the selected implementations' adapters (gate 1)."""
        msg = self._message_cb(on_message)
        bits = self._max_packet_bits()
        outcomes: list[BuildOutcome] = []
        for d in self._select_impls(impls, "build"):
            msg(f"building {d.manifest.name}")
            result = adapter.build(d, max_packet_bits=bits)
            outcomes.append(BuildOutcome(d.manifest.name, result.ok, result.stderr.strip()))
        return BuildRun(outcomes)

    def validate(
        self,
        impls: list[str] | None = None,
        datasets: list[str] | None = None,
        *,
        build: bool = True,
        on_message: MessageCB | None = None,
    ) -> ValidationRun:
        """Run round-trip, reference-vector and cross-impl checks via the contract."""
        msg = self._message_cb(on_message)
        selected_datasets = self._select_datasets(datasets)
        prepared = self._prepare(
            impls, command="validate", build=build, max_packet_bits=None, on_message=msg
        )
        if not prepared:
            return ValidationRun([])
        with tempfile.TemporaryDirectory(prefix="pocketbench-validate-") as tmp:
            results = validate_mod.validate(prepared, selected_datasets, workdir=Path(tmp))
        return ValidationRun(results)

    def bench(
        self,
        impls: list[str] | None = None,
        datasets: list[str] | None = None,
        *,
        warmup: int = 10,
        iterations: int = 100,
        build: bool = True,
        time_budget: float | None = None,
        on_message: MessageCB | None = None,
    ) -> BenchRun:
        """Run the uniform in-process benchmark for the selected impls/datasets.

        ``time_budget`` (seconds) sizes each measurement's iterations to fit it, between
        ``orchestrate.MIN_BUDGET_ITERATIONS`` and ``iterations``; None runs ``iterations`` everywhere.
        """
        if iterations < 1:
            raise PocketBenchError("iterations must be at least 1")
        if time_budget is not None and time_budget <= 0:
            raise PocketBenchError("time budget must be positive")
        msg = self._message_cb(on_message)
        selected_datasets = self._select_datasets(datasets)
        by_size: dict[int, list[Dataset]] = {}
        for ds in selected_datasets:
            by_size.setdefault(self._build_size(ds), []).append(ds)
        sizes = sorted(by_size)
        prepared = self._prepare(
            impls, command="bench", build=build, max_packet_bits=sizes[0] if sizes else None,
            on_message=msg,
        )

        def run(p: Prepared, group: list[Dataset], built_for: int | None) -> None:
            for ds in group:
                try:
                    measured = orchestrate.bench_metrics(
                        p, ds, warmup=warmup, iterations=iterations, time_budget=time_budget
                    )
                except RuntimeError as exc:
                    msg(f"{p.name}/{ds.name}: {exc}")
                    continue
                metrics.extend(replace(m, build_packet_bits=built_for) for m in measured)

        metrics: list[BenchMetrics] = []
        build_infos: list[BuildInfo] = []
        for p in prepared:
            if time_budget is None:
                msg(f"running {p.name} bench ({iterations} iter, {warmup} warmup)")
            else:
                msg(f"running {p.name} bench ({time_budget:g} s budget, at most {iterations} iter)")
            if not (build and p.adapter.manifest.size_specialized):
                build_infos.append(orchestrate.build_info(p, None))
                run(p, selected_datasets, None)
                continue
            for i, bits in enumerate(sizes):
                if i > 0:
                    msg(f"rebuilding {p.name} for F <= {bits} bits")
                    result = adapter.build(p.adapter, max_packet_bits=bits)
                    if not result.ok:
                        msg(f"{p.name}: rebuild for {bits} bits failed: {result.stderr.strip()}")
                        continue
                    # Self-reported limitations name the compiled-in size, so re-read them.
                    p = Prepared(adapter=p.adapter, capabilities=adapter.capabilities(p.adapter))
                build_infos.append(orchestrate.build_info(p, bits))
                run(p, by_size[bits], bits)
        return BenchRun(
            metrics=metrics,
            build_infos=build_infos,
            environment=report.collect_environment(),
            iterations=iterations,
            time_budget=time_budget,
        )

    def profile(
        self,
        impls: list[str] | None = None,
        datasets: list[str] | None = None,
        *,
        runs: int = 3,
        build: bool = True,
        on_message: MessageCB | None = None,
    ) -> ProfileRun:
        """Profile peak memory (RSS) and compression ratio via the adapter subprocess."""
        if not profiling.time_available():
            raise ProfileUnavailable(
                f"{profiling.TIME_BIN} not found; required for memory profiling"
            )
        msg = self._message_cb(on_message)
        selected_datasets = self._select_datasets(datasets)
        prepared = self._prepare(
            impls, command="profile", build=build, max_packet_bits=None, on_message=msg
        )

        results: list[ProfileResult] = []
        with tempfile.TemporaryDirectory(prefix="pocketbench-profile-") as tmp:
            workroot = Path(tmp)
            for p in prepared:
                for ds in selected_datasets:
                    msg(f"profiling {p.name} / {ds.name} ({runs} runs)")
                    try:
                        results.extend(
                            profiling.profile(p, ds, workroot / p.name / ds.name, runs=runs)
                        )
                    except RuntimeError as exc:
                        msg(f"{p.name}/{ds.name}: {exc}")
        return ProfileRun(results=results, environment=report.collect_environment(), runs=runs)

    def conformance(
        self,
        impls: list[str] | None = None,
        *,
        mode: str = "encoder",
        build: bool = True,
        limit: int = 0,
        jobs: int | None = None,
        results_dir: Path | None = None,
        on_progress: ProgressCB | None = None,
        on_message: MessageCB | None = None,
    ) -> ConformanceRun:
        """Run the UAB/CNES conformance suite for every selected implementation.

        Pass ``results_dir`` to persist a per-impl ``conformance-<impl>.txt`` log (the CLI does);
        with ``results_dir=None`` (the notebook default) no log is written. ``jobs`` vectors run at
        a time (default: one per CPU); the result does not depend on it.
        """
        if mode not in ("encoder", "decoder", "both"):
            raise ConformanceUnavailable("mode must be encoder, decoder or both")
        suite = self.config.conformance_suite
        if suite is None:
            raise ConformanceUnavailable(
                "no conformance dataset discovered under datasets/ (or its source did not resolve)"
            )
        if not suite.data_dir.exists():
            raise ConformanceUnavailable(f"conformance suite data missing: {suite.data_dir}")

        msg = self._message_cb(on_message)
        progress = self._progress_cb(on_progress)
        manifest = conformance.load_manifest(suite.manifest)
        prepared = self._prepare(
            impls, command="conformance", build=build, max_packet_bits=None, on_message=msg
        )

        results: list[ConformanceResult] = []
        for p in prepared:
            msg(f"running {p.name} conformance ({mode}, {suite.data_dir.name})")
            results_file = (
                results_dir / f"conformance-{p.name}.txt" if results_dir is not None else None
            )
            try:
                results.append(
                    conformance.run(
                        p,
                        data_dir=suite.data_dir,
                        manifest=manifest,
                        mode=mode,
                        results_file=results_file,
                        limit=limit or None,
                        on_progress=progress,
                        jobs=jobs,
                    )
                )
            except RuntimeError as exc:
                msg(str(exc))
        return ConformanceRun(results=results, mode=mode)

    def sweep(
        self,
        impls: list[str] | None,
        datasets: list[str] | None = None,
        *,
        pt: Sequence[int | None] | None = None,
        ft: Sequence[int | None] | None = None,
        rt: Sequence[int | None] | None = None,
        robustness: Sequence[int] | None = None,
        configs: Sequence[dict] | None = None,
        jobs: int | None = None,
        build: bool = True,
        on_progress: ProgressCB | None = None,
        on_message: MessageCB | None = None,
    ) -> SweepRun:
        """Compression ratio (exact bits in / out) per impl x dataset x configuration.

        Configurations are every combination of the given value lists, or ``configs``; a parameter
        neither sets keeps each dataset's own value, and None is off (the flag fires on the first
        packet only). ``jobs`` measurements run at a time (default: one per CPU); nothing is timed.
        """
        if not impls:
            raise PocketBenchError("sweep needs at least one implementation")
        configs = [dict(c) for c in configs] if configs is not None else None
        lists = {k: list(v) for k, v in zip(sweep_mod.PARAMS, (pt, ft, rt, robustness))
                 if v is not None}
        try:
            expanded = sweep_mod.expand(**lists, configs=configs)
        except ValueError as exc:
            raise PocketBenchError(str(exc)) from None
        selected = self._select_datasets(datasets)
        prepared = self._prepare(
            impls, command="sweep", build=build, max_packet_bits=None,
            on_message=self._message_cb(on_message),
        )
        results = sweep_mod.run(prepared, selected, expanded, jobs=jobs,
                                on_progress=self._progress_cb(on_progress))
        return SweepRun(
            results=results,
            parameters={"configs": configs} if configs is not None else lists,
            environment=report.collect_environment(),
        )

    def explain(
        self,
        vectors: str | Sequence[str],
        impls: list[str] | None = None,
        *,
        build: bool = True,
        timeout: int = 60,
        on_message: MessageCB | None = None,
    ) -> ExplainRun:
        """Re-run named conformance vectors and diff each against the suite's expected output.

        ``vectors`` accepts the name exactly as :attr:`ConformanceResult.failures` records it
        (``decoder_sequence_08724.raw+large_f``) or the bare stem (``decoder_sequence_08724``).
        Decoder vectors get a frame-level diff; encoder vectors get a byte-level one (their output
        is unframed). Raises :class:`VectorNotFound` for an unknown name.
        """
        names = [vectors] if isinstance(vectors, str) else list(vectors)
        if not names:
            raise VectorNotFound("no vector named")
        suite = self.config.conformance_suite
        if suite is None:
            raise ConformanceUnavailable(
                "no conformance dataset discovered under datasets/ (or its source did not resolve)"
            )
        if not suite.data_dir.exists():
            raise ConformanceUnavailable(f"conformance suite data missing: {suite.data_dir}")

        msg = self._message_cb(on_message)
        try:
            resolved = [explain_mod.resolve_vector(suite.data_dir, n) for n in names]
        except LookupError as exc:
            raise VectorNotFound(str(exc)) from None
        for v in resolved:
            if not v.expected_path.exists():
                raise VectorNotFound(f"suite has no expected output: {v.expected_path}")
        prepared = self._prepare(
            impls, command="explain", build=build, max_packet_bits=None, on_message=msg
        )

        results: list[ExplainResult] = []
        with tempfile.TemporaryDirectory() as tmp:
            for p in prepared:
                for v in resolved:
                    msg(f"explaining {v.name} for {p.name}")
                    vr = conformance.run_vector(p, v.phase, v.input_path, Path(tmp), timeout)
                    results.append(explain_mod.build_result(
                        impl=p.name, vector=v.name, phase=v.phase,
                        ran=vr.data is not None,
                        returncode=vr.run.returncode if vr.run.returncode is not None else -1,
                        stderr=vr.run.stderr if vr.data is not None
                        else f"{vr.run.reason}\n{vr.run.stderr}",
                        expected=v.expected_path.read_bytes(),
                        actual=vr.data or b"",
                        reason="" if vr.run.completed else vr.run.reason,
                    ))
        return ExplainRun(results=results)

    # -- persistence -------------------------------------------------------

    def write_report(self, run: BenchRun | ProfileRun | SweepRun) -> list[Path]:
        """Persist a run's report artifacts to ``settings.results_dir`` (opt-in).

        Benchmark runs write ``benchmark.md`` + ``benchmark.json``; profile runs write
        ``profile.json``. Build/validate/conformance produce no report files (conformance logs are opt-in
        via :meth:`conformance`'s ``results_dir``).
        """
        results_dir = self.config.settings.results_dir
        if isinstance(run, BenchRun):
            return [
                report.write_benchmark_markdown(
                    run.metrics, run.environment, run.build_infos, run.iterations, results_dir,
                    time_budget=run.time_budget,
                ),
                report.write_json(
                    run.metrics, run.environment, run.build_infos, run.iterations, results_dir,
                    time_budget=run.time_budget,
                ),
            ]
        if isinstance(run, ProfileRun):
            return [report.write_profile_json(run.results, run.environment, run.runs, results_dir)]
        if isinstance(run, SweepRun):
            return [report.write_sweep_json(run.results, run.parameters, run.environment,
                                            results_dir)]
        raise PocketBenchError(f"write_report does not handle {type(run).__name__}")
