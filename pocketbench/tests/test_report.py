"""Report rendering tests (pure, no adapters)."""

from rich.console import Console

from pocketbench import report
from pocketbench.benchmark import BenchMetrics, BuildInfo


def _metric(impl: str, packets_per_sec: float, *, operation="compress", vector="simple") -> BenchMetrics:
    return BenchMetrics(
        impl=impl, vector=vector, operation=operation, num_packets=100, size_bytes=9000,
        time_ms=1.0, us_per_pkt=1.0, packets_per_sec=packets_per_sec, mb_per_sec=packets_per_sec / 1000,
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
    text = _render(report.build_settings_table(infos, max_packet_bits=720))

    # Header states the packet size the build was sized for.
    assert "720 bits" in text and "90 bytes" in text
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
    text = _render(report.build_settings_table([info], max_packet_bits=720))
    assert "mock" in text
    # Empty optimization/limitations render as a dash, not blank.
    assert "-" in text
