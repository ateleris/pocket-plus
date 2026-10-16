"""Command-line interface for pocketbench.

Every command is a thin layer over :class:`pocketbench.api.PocketBench`: the class does all the work
and returns structured results, and the CLI only renders them and maps outcomes to exit codes, so
CLI and notebook behavior cannot diverge. Nothing here may hold logic of its own.
"""

from __future__ import annotations

from pathlib import Path
from typing import Annotated

import typer
from rich.console import Console
from rich.table import Table

from pocketbench import report
from pocketbench import sweep as sweep_mod
from pocketbench.api import (
    ConfigError,
    ConformanceUnavailable,
    PocketBench,
    PocketBenchError,
    ProfileUnavailable,
    SelectionError,
    VectorNotFound,
)
from pocketbench.explain import summary_lines
from pocketbench.orchestrate import ImplUnavailable

app = typer.Typer(
    add_completion=False,
    help="Test, validate, profile and visualize CCSDS 124.0-B-1 implementations.",
)
console = Console()

DEFAULT_CONFIG = Path("config/config.toml")

ConfigOpt = Annotated[
    Path, typer.Option("--config", "-c", help="Path to the TOML config file.")
]
ImplOpt = Annotated[
    list[str] | None,
    typer.Option(
        "--impl", "-i",
        help="Implementation to use (repeatable; globs like 'pocketrust-*' allowed). Default: all.",
    ),
]
DatasetOpt = Annotated[
    list[str] | None,
    typer.Option(
        "--dataset", "-d",
        help="Dataset to use (repeatable; globs like 'reference-*' allowed). Default: all.",
    ),
]


def _load(config: Path) -> PocketBench:
    """Build a PocketBench that prints progress/skips to the console."""
    try:
        return PocketBench(
            config,
            on_progress=lambda phase, done, total: console.print(f"[dim]  {phase}: {done}/{total}[/dim]"),
            on_message=lambda m: console.print(f"[yellow]{m}[/yellow]"),
        )
    except ConfigError as exc:
        console.print(f"[red]{exc}[/red]")
        raise typer.Exit(code=2) from None


def _count(items: list, noun: str) -> str:
    return f"{len(items)} {noun}{'' if len(items) == 1 else 's'}"


def _show_selection(
    pb: PocketBench, impls: list[str] | None, datasets: list[str] | None, *, command: str,
    verbose: bool,
):
    """Print the selection (a count, or with ``verbose`` the full table), exiting 2 on an unknown
    name."""
    try:
        selected_impls = pb.config.select_impls(impls, command=command)
        selected_datasets = pb.config.select_datasets(datasets)
    except KeyError as exc:
        console.print(f"[red]{exc.args[0]}[/red]")
        raise typer.Exit(code=2) from None

    if not verbose:
        console.print(f"Selection: {_count(selected_impls, 'implementation')}, "
                      f"{_count(selected_datasets, 'dataset')}")
        return
    table = Table(title="Selection", show_header=True, header_style="bold")
    table.add_column("Implementations")
    table.add_column("Datasets")
    rows = max(len(selected_impls), len(selected_datasets))
    for row in range(rows):
        left = selected_impls[row].manifest.name if row < len(selected_impls) else ""
        right = selected_datasets[row].name if row < len(selected_datasets) else ""
        table.add_row(left, right)
    console.print(table)


@app.command()
def build(config: ConfigOpt = DEFAULT_CONFIG, impl: ImplOpt = None):
    """Build the selected implementations' adapters (gate 1)."""
    pb = _load(config)
    try:
        run = pb.build(impl)
    except SelectionError as exc:
        console.print(f"[red]{exc}[/red]")
        raise typer.Exit(code=2) from None
    for o in run.results:
        if o.ok:
            console.print(f"[green]ok[/green] {o.impl}")
        else:
            console.print(f"[red]{o.impl}: build failed[/red]\n{o.stderr}")
    if not run.ok:
        raise typer.Exit(code=1)


@app.command()
def validate(
    config: ConfigOpt = DEFAULT_CONFIG,
    impl: ImplOpt = None,
    dataset: DatasetOpt = None,
    build: Annotated[bool, typer.Option(help="Build implementations before validating.")] = True,
    verbose: Annotated[
        bool, typer.Option("--verbose", "-v", help="List every selected impl and dataset.")
    ] = False,
):
    """Run round-trip, reference-vector and cross-impl checks via the contract."""
    pb = _load(config)
    _show_selection(pb, impl, dataset, command="validate", verbose=verbose)
    try:
        run = pb.validate(impl, dataset, build=build)
    except (SelectionError, ImplUnavailable) as exc:
        console.print(f"[red]{exc}[/red]")
        raise typer.Exit(code=1 if isinstance(exc, ImplUnavailable) else 2) from None

    if not run.results:
        console.print("[red]No implementations available to validate.[/red]")
        raise typer.Exit(code=1)

    table = Table(title="Validation", header_style="bold")
    for col in ("Implementation", "Dataset", "Check", "Result", "Detail"):
        table.add_column(col)
    for r in run.results:
        mark = "[green]PASS[/green]" if r.passed else "[red]FAIL[/red]"
        table.add_row(r.impl, r.dataset, r.check, mark, r.detail)
    console.print(table)

    failures = [r for r in run.results if not r.passed]
    total = len(run.results)
    if failures:
        console.print(f"[red]{len(failures)}/{total} checks failed.[/red]")
        raise typer.Exit(code=1)
    console.print(f"[green]All {total} checks passed.[/green]")


@app.command()
def bench(
    config: ConfigOpt = DEFAULT_CONFIG,
    impl: ImplOpt = None,
    dataset: DatasetOpt = None,
    warmup: Annotated[int, typer.Option(help="Untimed warmup iterations per run.")] = 10,
    iterations: Annotated[int, typer.Option(help="Timed iterations per run.")] = 100,
    build: Annotated[bool, typer.Option(help="Build adapters before running.")] = True,
    time_budget: Annotated[
        float | None,
        typer.Option(
            "--time-budget",
            help="Seconds of timed work per measurement: iterations are sized to fit, at least 5 "
            "and at most --iterations, warmup scaled to match. Default: --iterations everywhere.",
        ),
    ] = None,
    verbose: Annotated[
        bool,
        typer.Option(
            "--verbose", "-v",
            help="List the selection and add a table per dataset and operation.",
        ),
    ] = False,
):
    """Run the uniform in-process benchmark for the selected impls/datasets via the contract."""
    pb = _load(config)
    _show_selection(pb, impl, dataset, command="bench", verbose=verbose)
    try:
        run = pb.bench(impl, dataset, warmup=warmup, iterations=iterations, build=build,
                       time_budget=time_budget)
    except ImplUnavailable as exc:
        console.print(f"[red]{exc}[/red]")
        raise typer.Exit(code=1) from None
    except PocketBenchError as exc:
        console.print(f"[red]{exc}[/red]")
        raise typer.Exit(code=2) from None

    if not run.metrics:
        console.print("[red]No benchmark results for the selected implementations/datasets.[/red]")
        raise typer.Exit(code=1)

    if verbose:
        for table in report.benchmark_tables(run.metrics):
            console.print(table)

    console.print(report.overview_table(run.metrics))
    console.print(report.build_settings_table(run.build_infos))

    md, js = pb.write_report(run)
    console.print(f"[green]Wrote[/green] {md}\n[green]Wrote[/green] {js}")


@app.command()
def profile(
    config: ConfigOpt = DEFAULT_CONFIG,
    impl: ImplOpt = None,
    dataset: DatasetOpt = None,
    runs: Annotated[int, typer.Option(help="Timed runs per operation (peak RSS is the max).")] = 3,
    build: Annotated[bool, typer.Option(help="Build implementations before profiling.")] = True,
    verbose: Annotated[
        bool, typer.Option("--verbose", "-v", help="List every selected impl and dataset.")
    ] = False,
):
    """Profile peak memory (RSS) and compression ratio via the adapter subprocess."""
    pb = _load(config)
    _show_selection(pb, impl, dataset, command="profile", verbose=verbose)
    try:
        run = pb.profile(impl, dataset, runs=runs, build=build)
    except ProfileUnavailable as exc:
        console.print(f"[red]{exc}[/red]")
        raise typer.Exit(code=1) from None
    except (SelectionError, ImplUnavailable) as exc:
        console.print(f"[red]{exc}[/red]")
        raise typer.Exit(code=1 if isinstance(exc, ImplUnavailable) else 2) from None

    if not run.results:
        console.print("[red]No profile results.[/red]")
        raise typer.Exit(code=1)

    for table in report.profile_tables(run.results):
        console.print(table)
    (js,) = pb.write_report(run)
    console.print(f"[green]Wrote[/green] {js}")


def _values(spec: str | None, name: str, *, allow_off: bool = True) -> list[int | None] | None:
    if spec is None:
        return None
    try:
        return sweep_mod.parse_values(spec, allow_off=allow_off)
    except ValueError as exc:
        console.print(f"[red]--{name}: {exc}[/red]")
        raise typer.Exit(code=2) from None


_SPEC_HELP = "Values to sweep: integers, ranges a-b and 'off' (first packet only), comma-separated."


@app.command()
def sweep(
    config: ConfigOpt = DEFAULT_CONFIG,
    impl: ImplOpt = None,
    dataset: DatasetOpt = None,
    pt: Annotated[str | None, typer.Option("--pt", help=_SPEC_HELP)] = None,
    ft: Annotated[str | None, typer.Option("--ft", help=_SPEC_HELP)] = None,
    rt: Annotated[str | None, typer.Option("--rt", help=_SPEC_HELP)] = None,
    robustness: Annotated[
        str | None, typer.Option("--robustness", help="Values to sweep: integers and ranges a-b.")
    ] = None,
    jobs: Annotated[
        int | None, typer.Option(help="Measurements run at a time. Default: one per CPU.")
    ] = None,
    build: Annotated[bool, typer.Option(help="Build implementations before sweeping.")] = True,
    verbose: Annotated[
        bool,
        typer.Option("--verbose", "-v", help="List the selection and add a row per dataset."),
    ] = False,
):
    """Compression ratio (exact bits) for every combination of the given parameter values."""
    values = {
        "pt": _values(pt, "pt"),
        "ft": _values(ft, "ft"),
        "rt": _values(rt, "rt"),
        "robustness": _values(robustness, "robustness", allow_off=False),
    }
    if not impl:
        console.print("[red]sweep needs --impl (e.g. --impl pocketrust-u64)[/red]")
        raise typer.Exit(code=2)
    pb = _load(config)
    _show_selection(pb, impl, dataset, command="sweep", verbose=verbose)
    try:
        run = pb.sweep(impl, dataset, **values, jobs=jobs, build=build)
    except ImplUnavailable as exc:
        console.print(f"[red]{exc}[/red]")
        raise typer.Exit(code=1) from None
    except PocketBenchError as exc:
        console.print(f"[red]{exc}[/red]")
        raise typer.Exit(code=2) from None

    if verbose:
        console.print(report.sweep_detail_table(run.results))
    console.print(report.sweep_table(run.results))
    for line in report.sweep_incomplete_lines(run.results):
        console.print(f"[yellow]{line}[/yellow]")
    (js,) = pb.write_report(run)
    console.print(f"[green]Wrote[/green] {js}")


@app.command()
def conformance(
    config: ConfigOpt = DEFAULT_CONFIG,
    impl: ImplOpt = None,
    mode: Annotated[str, typer.Option(help="encoder | decoder | both.")] = "encoder",
    build: Annotated[bool, typer.Option(help="Build conformance harnesses first.")] = True,
    limit: Annotated[int, typer.Option(help="Only the first N vectors per phase (0 = all).")] = 0,
    jobs: Annotated[int, typer.Option(help="Vectors run at a time (0 = one per CPU).")] = 0,
):
    """Run the UAB/CNES conformance suite for the selected implementations."""
    pb = _load(config)
    try:
        run = pb.conformance(
            impl, mode=mode, build=build, limit=limit, jobs=jobs or None,
            results_dir=pb.config.settings.results_dir,
        )
    except ConformanceUnavailable as exc:
        console.print(f"[red]{exc}[/red]")
        raise typer.Exit(code=2) from None
    except (SelectionError, ImplUnavailable) as exc:
        console.print(f"[red]{exc}[/red]")
        raise typer.Exit(code=1 if isinstance(exc, ImplUnavailable) else 2) from None

    if not run.results:
        console.print("[red]No implementations selected.[/red]")
        raise typer.Exit(code=1)

    table = Table(title=f"Conformance ({mode})", header_style="bold")
    for col in ("Implementation", "Total", "Passed", "Failed"):
        table.add_column(col, justify="left" if col == "Implementation" else "right")
    for r in run.results:
        table.add_row(r.impl, f"{r.total:,}", f"{r.passed:,}", f"{r.failed:,}")
    console.print(table)
    for r in run.results:
        console.print(f"[dim]{r.impl}: full log -> {r.results_file}[/dim]")


@app.command()
def explain(
    vectors: Annotated[list[str], typer.Argument(help="Failing vector name(s), stem or full name.")],
    config: ConfigOpt = DEFAULT_CONFIG,
    impl: ImplOpt = None,
    build: Annotated[bool, typer.Option(help="Build the adapters first.")] = True,
    frame: Annotated[int, typer.Option(help="Also dump this decoder frame (-1 = the first divergence).")] = -2,
    mode: Annotated[str, typer.Option(help="Frame dump format: hex | bin.")] = "hex",
    full: Annotated[bool, typer.Option(help="Dump the whole frame, not only differing lines.")] = False,
):
    """Re-run named conformance vectors and report what diverged from the reference output."""
    pb = _load(config)
    try:
        run = pb.explain(vectors, impl, build=build)
    except (ConformanceUnavailable, VectorNotFound) as exc:
        console.print(f"[red]{exc}[/red]")
        raise typer.Exit(code=2) from None
    except (SelectionError, ImplUnavailable) as exc:
        console.print(f"[red]{exc}[/red]")
        raise typer.Exit(code=1 if isinstance(exc, ImplUnavailable) else 2) from None

    if not run.results:
        console.print("[red]No implementations selected.[/red]")
        raise typer.Exit(code=1)

    for r in run.results:
        for line in summary_lines(r):
            # soft_wrap: these lines are column-aligned by hand, so rich must not re-wrap them.
            console.print(line, highlight=False, soft_wrap=True)
        if frame != -2:
            wanted = r.decoder.first_divergence if frame == -1 and r.decoder else frame
            if wanted is None:
                console.print("  [dim]no divergence to dump[/dim]")
            else:
                try:
                    console.print(
                        r.show_frame(wanted, mode=mode, full=full),
                        highlight=False, soft_wrap=True,
                    )
                except ValueError as exc:
                    console.print(f"  [yellow]{exc}[/yellow]")
        console.print()


@app.command("report")
def report_cmd(config: ConfigOpt = DEFAULT_CONFIG):
    """Render charts and an HTML report from the latest results."""
    pb = _load(config)
    console.print(f"[yellow]report: not yet implemented[/yellow] (-> {pb.config.settings.results_dir})")
    raise typer.Exit(code=0)


if __name__ == "__main__":
    app()
