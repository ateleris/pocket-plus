"""Report rendering tests (pure, no adapters)."""

import dataclasses

from rich.console import Console

from pocketbench import report
from pocketbench.benchmark import BenchMetrics, BuildInfo


def _metric(impl: str, packets_per_sec: float, *, operation="compress", vector="simple",
            build_packet_bits: int | None = None) -> BenchMetrics:
    return BenchMetrics(
        impl=impl, vector=vector, operation=operation, num_packets=100, size_bytes=9000,
        time_ms=1.0, us_per_pkt=1.0, packets_per_sec=packets_per_sec, mb_per_sec=packets_per_sec / 1000,
        build_packet_bits=build_packet_bits,
    )


def _render(table) -> str:
    # Render wide so nothing is ellipsized, then read the plain text back.
    console = Console(width=200, file=None, record=True)
    console.print(table)
    return console.export_text()


def test_build_settings_table_shows_per_impl_build_args():
    infos = [
        BuildInfo(
            impl="reference-c",
            language="C",
            toolchain="gcc 13.3.0",
            clean_command=None,
            build_command="make MAX_PACKET_BITS=720 SOURCE=/x/c",
            build_profile="gcc -std=c99 -O3 -flto",
            limitations="packets up to CCSDS124_MAX_PACKET_LENGTH=720 bits; larger unsupported",
            size_specialized=True,
            packet_bits=720,
        ),
        BuildInfo(
            impl="reference-cpp",
            language="C++",
            toolchain="g++ 13.3.0",
            clean_command=None,
            build_command="make SOURCE=/x/cpp",
            build_profile="g++ -std=c++17 -O3 -flto",
            limitations="compiled for 90-byte (720-bit) packets only",
        ),
    ]
    text = _render(report.build_settings_table(infos))

    # Each build says which F it was compiled for, or that its buffers are sized at run time.
    assert "F ≤ 720 bits (compile-time)" in text
    assert "any F (run time)" in text
    # Each impl's build command (its build arguments) is present.
    assert "make MAX_PACKET_BITS=720 SOURCE=/x/c" in text
    assert "make SOURCE=/x/cpp" in text
    # Toolchain and language are shown too.
    assert "gcc 13.3.0" in text
    assert "C++" in text
    assert "-std=c99 -O3 -flto" in text
    assert "CCSDS124_MAX_PACKET_LENGTH=720 bits" in text
    assert "720-bit) packets only" in text


def test_benchmark_table_highlights_fastest_impl_per_dataset_in_yellow():
    # Two datasets x compress; the fastest impl differs per dataset. Each table's fastest row
    # (max packets/sec) is styled yellow; the others carry no row style.
    metrics = [
        _metric("reference-c", 1000, vector="simple"),
        _metric("pocketrust", 3000, vector="simple"),
        _metric("reference-rust", 2000, vector="simple"),
        _metric("reference-c", 5000, vector="hiro"),
        _metric("pocketrust", 2500, vector="hiro"),
    ]
    tables = {t.title.split(":")[1].split("(")[0].strip(): t for t in report.benchmark_tables(metrics)}

    simple_styles = [(r.style, m.impl) for r, m in zip(tables["simple"].rows,
                     [m for m in metrics if m.vector == "simple"])]
    assert [s for s, _ in simple_styles] == [None, "yellow", None]  # pocketrust fastest
    assert dict((i, s) for s, i in simple_styles)["pocketrust"] == "yellow"

    hiro_styles = [(r.style, m.impl) for r, m in zip(tables["hiro"].rows,
                   [m for m in metrics if m.vector == "hiro"])]
    assert [s for s, _ in hiro_styles] == ["yellow", None]  # reference-c fastest here


def test_build_settings_table_falls_back_when_fields_empty():
    info = BuildInfo(
        impl="mock",
        language="Python",
        toolchain="python 3.11",
        clean_command=None,
        build_command="(none)",
    )
    text = _render(report.build_settings_table([info]))
    assert "mock" in text
    # Empty optimization/limitations render as a dash, not blank.
    assert "-" in text


def test_build_settings_table_no_build_does_not_claim_a_size():
    info = BuildInfo(impl="reference-c", language="C", toolchain="gcc", clean_command=None,
                     build_command="(not rebuilt: --no-build)", size_specialized=True)
    assert "as last built (--no-build)" in _render(report.build_settings_table([info]))


def test_benchmark_rows_name_the_size_specialized_build():
    metrics = [_metric("reference-c", 1000, build_packet_bits=720), _metric("pocketrust", 900)]
    text = _render(report.benchmark_tables(metrics)[0])
    assert "reference-c (built for F ≤ 720)" in text
    assert "pocketrust " in text and "pocketrust (" not in text


def test_markdown_explains_size_specialized_builds(tmp_path):
    infos = [
        BuildInfo(impl="reference-c", language="C", toolchain="gcc", clean_command=None,
                  build_command="make MAX_PACKET_BITS=720", size_specialized=True, packet_bits=720),
        BuildInfo(impl="reference-c", language="C", toolchain="gcc", clean_command=None,
                  build_command="make MAX_PACKET_BITS=65535", size_specialized=True,
                  packet_bits=65535),
        BuildInfo(impl="pocketrust", language="Rust", toolchain="cargo", clean_command=None,
                  build_command="cargo build --release"),
    ]
    metrics = [_metric("reference-c", 1000, build_packet_bits=720), _metric("pocketrust", 900)]
    text = report.write_benchmark_markdown(metrics, {}, infos, 10, tmp_path).read_text()
    assert "compile-time advantage" in text
    assert "reference-c" in text.split("compile-time advantage")[0].rsplit("\n\n", 1)[-1]
    assert "| reference-c (built for F ≤ 720) |" in text
    assert "### reference-c (C), built for F ≤ 65535 bits" in text


def test_markdown_has_no_specialization_note_without_specialized_builds(tmp_path):
    infos = [BuildInfo(impl="pocketrust", language="Rust", toolchain="cargo", clean_command=None,
                       build_command="cargo build --release")]
    text = report.write_benchmark_markdown([_metric("pocketrust", 900)], {}, infos, 10, tmp_path)
    assert "compile-time advantage" not in text.read_text()


def _overview_metrics():
    return [
        _metric("reference-c", 1000, vector="simple"),
        _metric("pocketrust", 3000, vector="simple"),
        _metric("reference-c", 5000, vector="hiro"),
        _metric("reference-c", 2000, vector="simple", operation="decompress"),
        _metric("pocketrust", 4000, vector="simple", operation="decompress"),
        _metric("reference-c", 6000, vector="hiro", operation="decompress"),
    ]


def test_overview_table_has_one_row_per_dataset_and_a_column_per_operation_and_impl():
    table = report.overview_table(_overview_metrics())
    assert [str(c.header) for c in table.columns] == [
        "Dataset", "Compress\nreference-c", "\npocketrust", "Decompress\nreference-c", "\npocketrust",
    ]
    cells = [list(c._cells) for c in table.columns]
    assert cells[0] == ["simple", "hiro"]
    assert cells[1:] == [
        ["1.00", "[yellow]5.00[/yellow]"],
        ["[yellow]3.00[/yellow]", "-"],
        ["2.00", "[yellow]6.00[/yellow]"],
        ["[yellow]4.00[/yellow]", "-"],
    ]


def test_markdown_overview_groups_impls_under_each_operation_before_build_settings(tmp_path):
    text = report.write_benchmark_markdown(_overview_metrics(), {}, [], 10, tmp_path).read_text()
    overview = text.split("## Throughput Overview (MB/s)")[1].split("## Build Settings")[0]
    assert '<th colspan="2">Compress</th><th colspan="2">Decompress</th>' in overview
    assert ("<th>reference-c</th><th>pocketrust</th>"
            "<th>reference-c</th><th>pocketrust</th>") in overview
    assert ("<tr><td>hiro</td><td><b>5.00</b></td><td>-</td>"
            "<td><b>6.00</b></td><td>-</td></tr>") in overview


def test_detail_tables_show_each_rows_iteration_count(tmp_path):
    m = dataclasses.replace(_metric("pocketrust", 900), iterations=7)
    assert "Iterations" in _render(report.benchmark_tables([m])[0])
    text = report.write_benchmark_markdown([m], {}, [], 100, tmp_path).read_text()
    assert "| Iterations |" in text and " | 7 |" in text


def test_methodology_states_the_time_budget(tmp_path):
    m = _metric("pocketrust", 900)
    budget = report.write_benchmark_markdown([m], {}, [], 100, tmp_path, time_budget=1.5)
    assert "time budget of 1.5 s per measurement (5 to 100 timed iterations" in budget.read_text()
    fixed = report.write_benchmark_markdown([m], {}, [], 100, tmp_path).read_text()
    assert "- Iterations: 100" in fixed


def test_json_records_the_time_budget(tmp_path):
    import json

    path = report.write_json([_metric("pocketrust", 900)], {}, [], 100, tmp_path, time_budget=2.0)
    payload = json.loads(path.read_text())
    assert (payload["iterations"], payload["time_budget"]) == (100, 2.0)
