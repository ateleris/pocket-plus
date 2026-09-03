"""Driver tests: pocketbench core talking to the mock adapter over the contract."""

from pathlib import Path

from pocketbench import adapter
from pocketbench.adapter.discovery import DiscoveredAdapter, discover_adapters

REPO = Path(__file__).resolve().parents[1]


def _mock() -> DiscoveredAdapter:
    found = discover_adapters(REPO / "adapters", repo_root=REPO, variables={})
    (mock,) = [d for d in found if d.manifest.name == "mock"]
    return mock


def test_capabilities_parsed_from_mock():
    caps = adapter.capabilities(_mock())
    assert caps.timing_tier == "in_process"
    assert caps.ops == ["compress", "decompress"]
    assert caps.reference_conformant is True
    assert caps.conformance_compress is True
    assert caps.conformance_decompress is True
    assert caps.param_schedule == "identity"


def test_build_noop_when_no_build_command():
    result = adapter.build(_mock())
    assert result.ok


def test_compress_and_decompress_write_exact_output_paths(tmp_path):
    from pocketbench.adapter import Params

    d = _mock()
    inp = tmp_path / "in.bin"
    inp.write_bytes(b"HELLO-WORLD!")
    params = Params(packet_bits=16, pt=0, ft=0, rt=0, robustness=0)

    pkt = tmp_path / "out.pkt"
    assert adapter.compress(d, params, inp, pkt).ok
    assert pkt.exists()

    depkt = tmp_path / "out.depkt"
    assert adapter.decompress(d, params, pkt, depkt).ok
    # Identity codec: round-trip recovers the original bytes exactly.
    assert depkt.read_bytes() == inp.read_bytes()


def test_conformance_compress_decompress_write_output(tmp_path):
    d = _mock()
    src = tmp_path / "v.raw+config"
    src.write_bytes(b"VECTOR")
    out = tmp_path / "v.124"
    assert adapter.conformance_compress(d, src, out).ok
    assert out.read_bytes() == b"VECTOR"

    out2 = tmp_path / "v.raw+large_f"
    assert adapter.conformance_decompress(d, out, out2).ok
    assert out2.read_bytes() == b"VECTOR"


def test_bench_returns_raw_nanos_payload(tmp_path):
    from pocketbench.adapter import Params

    d = _mock()
    inp = tmp_path / "in.bin"
    inp.write_bytes(b"0123456789")  # 10 bytes, 16-bit (2-byte) packets -> 5 packets/iter
    params = Params(packet_bits=16, pt=0, ft=0, rt=0, robustness=0)

    payload = adapter.bench(d, "compress", inp, params, warmup=3, iterations=20)
    assert payload.op == "compress"
    assert payload.iterations == 20
    assert payload.packets_per_iter == 5
    assert len(payload.nanos) == 20
    assert all(isinstance(n, int) for n in payload.nanos)


def test_to_metrics_derives_from_median_nanos():
    from pocketbench.adapter import BenchPayload, to_metrics

    # Median of these nanos is 4000 ns = 4.0 us/iter over 5 packets, 2-byte packets.
    payload = BenchPayload(op="compress", iterations=5, packets_per_iter=5,
                           nanos=[3000, 3500, 4000, 4500, 5000])
    m = to_metrics(payload, impl_name="mock", vector="d", packet_bits=16)
    assert m.impl == "mock"
    assert m.vector == "d"
    assert m.operation == "compress"
    assert m.num_packets == 5
    assert m.time_ms == 0.004  # 4.0 us / 1000
    assert m.us_per_pkt == 4.0 / 5
    assert m.size_bytes == 10  # 5 packets * 2 bytes


def test_mock_adapter_conforms_to_contract(tmp_path):
    from tests.adapter_contract import assert_adapter_conforms

    assert_adapter_conforms(_mock(), tmp_path=tmp_path)
