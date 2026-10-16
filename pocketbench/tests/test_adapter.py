"""Driver tests: pocketbench core talking to the mock adapter over the contract."""

from pathlib import Path

from pocketbench import adapter, records
from pocketbench.adapter import VerbRun
from pocketbench.adapter.discovery import DiscoveredAdapter, discover_adapters
from pocketbench.records import Container, Record
from tests.conftest import scripted_adapter

REPO = Path(__file__).resolve().parents[1]


def _mock() -> DiscoveredAdapter:
    found = discover_adapters(REPO / "adapters", repo_root=REPO, variables={})
    (mock,) = [d for d in found if d.manifest.name == "mock"]
    return mock


def _input(tmp_path: Path) -> Path:
    inp = tmp_path / "in.rec"
    inp.write_bytes(records.encode(Container(16, (Record(0, 16, b"\0\0"), Record(0, 16, b"AB")))))
    return inp


def test_capabilities_are_report_text_only():
    caps = adapter.capabilities(_mock())
    assert caps == adapter.Capabilities(
        build_profile="", limitations="identity test double: every packet is its own field"
    )


def test_build_noop_when_no_build_command():
    assert adapter.build(_mock()).ok


def test_verb_argv_is_keyed(tmp_path):
    argv = adapter.verb_argv(_mock(), "compress", tmp_path / "i", tmp_path / "o", warmup=2, iterations=3)
    assert argv[-5:] == [
        "compress", f"--in={tmp_path / 'i'}", f"--out={tmp_path / 'o'}", "--warmup=2", "--iterations=3",
    ]


def test_run_verb_reads_summary_and_output(tmp_path):
    run = adapter.run_verb(_mock(), "compress", _input(tmp_path), tmp_path / "out.rec")
    assert run.completed and run.reason == "ok"
    assert run.output == Container(16, (Record(0, 16, b"AB"),))
    assert run.summary == {"records_in": 2, "records_out": 1, "stopped_at": None, "error": None}


def test_bench_returns_raw_nanos_payload(tmp_path):
    payload = adapter.bench(
        _mock(), "compress", _input(tmp_path), tmp_path / "out.rec", warmup=3, iterations=20, packets=1
    )
    assert payload.op == "compress"
    assert payload.iterations == 20
    assert payload.packets_per_iter == 1
    assert len(payload.nanos) == 20 and all(isinstance(n, int) for n in payload.nanos)


def test_to_metrics_derives_from_median_nanos():
    from pocketbench.adapter import BenchPayload, to_metrics

    # Median of these nanos is 4000 ns = 4.0 us/iter over 5 packets, 2-byte packets.
    payload = BenchPayload(op="compress", iterations=5, packets_per_iter=5,
                           nanos=[3000, 3500, 4000, 4500, 5000])
    m = to_metrics(payload, impl_name="mock", vector="d", packet_bits=16)
    assert m.impl == "mock" and m.vector == "d" and m.operation == "compress"
    assert m.num_packets == 5
    assert m.time_ms == 0.004
    assert m.us_per_pkt == 4.0 / 5
    assert m.size_bytes == 10


def test_reason_names_each_failure_mode():
    assert VerbRun(None, "", None, None).reason == "timeout"
    assert VerbRun(-11, "", None, None).reason == "crashed: signal 11"
    assert VerbRun(1, "boom\nmore", None, None).reason == "exit 1: boom"
    assert VerbRun(1, "", None, None).reason == "exit 1"
    assert VerbRun(0, "", None, None, "bad").reason == "malformed output: bad"
    assert VerbRun(0, "", {"stopped_at": None, "error": None}, None).reason == "no output written"
    assert VerbRun(0, "", None, Container(16)).reason == "no summary printed"
    stopped = VerbRun(0, "", {"stopped_at": 2, "error": "codec error 7"}, Container(16))
    assert stopped.reason == "stopped: codec error 7" and not stopped.completed


def test_a_crashing_adapter_is_reported_as_crashed(tmp_path):
    d = scripted_adapter(tmp_path, "os.kill(os.getpid(), 9)")
    run = adapter.run_verb(d, "compress", _input(tmp_path), tmp_path / "out.rec")
    assert run.reason == "crashed: signal 9" and run.output is None


def test_a_hanging_adapter_times_out(tmp_path):
    d = scripted_adapter(tmp_path, "time.sleep(30)")
    run = adapter.run_verb(d, "compress", _input(tmp_path), tmp_path / "out.rec", timeout=1)
    assert run.reason == "timeout"


def test_malformed_adapter_output_is_reported_not_raised(tmp_path):
    d = scripted_adapter(
        tmp_path,
        "out = next(a[6:] for a in sys.argv if a.startswith('--out='))\n"
        "Path(out).write_bytes(b'\\x00\\x00\\x00\\x10\\x00')\n"
        "print('{\"records_in\": 2, \"records_out\": 1, \"stopped_at\": null, \"error\": null}')",
    )
    run = adapter.run_verb(d, "compress", _input(tmp_path), tmp_path / "out.rec")
    assert run.output is None
    assert run.reason == "malformed output: truncated record header at byte 4"


def test_a_non_object_summary_is_reported_not_raised(tmp_path):
    d = scripted_adapter(
        tmp_path,
        "out = next(a[6:] for a in sys.argv if a.startswith('--out='))\n"
        "Path(out).write_bytes(b'\\x00\\x00\\x00\\x10')\n"
        "print('1')",
    )
    run = adapter.run_verb(d, "compress", _input(tmp_path), tmp_path / "out.rec")
    assert not run.completed
    assert run.reason == "no summary printed"
