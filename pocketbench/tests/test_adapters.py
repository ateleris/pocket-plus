"""Every real adapter, driven over the contract from one declarative table.

One test body per behaviour, parametrized over ADAPTERS: adding an adapter means adding a row, and
it then inherits the whole suite. Every test skips, never fails, when the codec is absent.
"""

from __future__ import annotations

import tomllib
from dataclasses import dataclass
from pathlib import Path

import pytest

from pocketbench import adapter
from pocketbench.adapter import Params

from tests.adapter_contract import assert_adapter_conforms
from tests.conftest import built_adapter, ccsds124_root

REPO = Path(__file__).resolve().parents[1]
CCSDS124 = ccsds124_root()

# simple.bin's params, from its expected-output metadata.json.
SIMPLE = Params(packet_bits=720, pt=10, ft=20, rt=50, robustness=1)


@dataclass(frozen=True)
class Spec:
    """What is impl-specific about one adapter. Everything else is the shared contract."""

    name: str
    build_profile: str      # substring of the self-reported build_profile
    limitations: str        # substring of the self-reported limitations
    conformance: bool       # runs the UAB/CNES phases
    sub_byte_f: bool        # accepts an F that is not a multiple of 8
    # An F the adapter cannot handle although it is byte-aligned (reference-cpp instantiates its
    # packet length at compile time); None when every byte-aligned F is supported.
    unsupported_packet_bits: int | None = None
    # Compile-time CCSDS124_MAX_PACKET_LENGTH; None = the codec default. Only reference-c reads it.
    max_packet_bits: int | None = None
    param_schedule: str = "pt_ft_rt"


ADAPTERS = [
    Spec("reference-c", "-O3", "CCSDS124_MAX_PACKET_LENGTH=", conformance=True, sub_byte_f=True),
    Spec("reference-cpp", "-O3", "720-bit", conformance=False, sub_byte_f=False,
         unsupported_packet_bits=512),
    Spec("reference-rust", "opt-level=3", "whole-buffer public API only",
         conformance=False, sub_byte_f=False),
    Spec("pocketrust", "opt-level=3", "portable", conformance=True, sub_byte_f=True),
]

# Named ids so `-k reference-c` selects one adapter's rows.
impl = pytest.mark.parametrize("spec", ADAPTERS, ids=[s.name for s in ADAPTERS])


def _built(spec: Spec):
    return built_adapter(spec.name, spec.max_packet_bits)


def _packets(count: int, step: int = 7, packet_bytes: int = 90) -> bytes:
    return bytes((i * step) % 256 for i in range(packet_bytes * count))


def _conformance_dir() -> Path | None:
    cfg = REPO / "config" / "config.toml"
    if not cfg.exists():
        return None
    raw = tomllib.loads(cfg.read_text()).get("settings", {}).get("conformance_data_dir")
    return Path(raw) if raw else None


def _uab_vector(kind: str, stem: str, in_ext: str, out_ext: str) -> tuple[Path, Path]:
    """One UAB/CNES vector plus its expected output, or skip if the suite is not present.

    Extensions are named, not globbed: the suite dirs hold tens of thousands of files and several
    share a stem.
    """
    d = _conformance_dir()
    if not d or not (d / f"{kind}_input").is_dir():
        pytest.skip(f"conformance_data_dir / {kind}_input not present")
    vec = d / f"{kind}_input" / f"{stem}.{in_ext}"
    expected = d / f"{kind}_output" / f"{stem}.{out_ext}"
    if not (vec.exists() and expected.exists()):
        pytest.skip(f"UAB {kind} vector {stem} not present")
    return vec, expected


# --- capabilities -------------------------------------------------------------------------------


@impl
def test_capabilities(spec):
    caps = adapter.capabilities(_built(spec))
    assert caps.ops == ["compress", "decompress"]
    assert caps.timing_tier == "in_process"
    assert caps.reference_conformant is True
    assert caps.conformance_compress is spec.conformance
    assert caps.conformance_decompress is spec.conformance
    assert caps.param_schedule == spec.param_schedule
    # Self-reported build facts, so they cannot drift from what the binary was compiled with.
    assert spec.build_profile in caps.build_profile
    assert spec.limitations in caps.limitations


# --- compress / decompress ----------------------------------------------------------------------


@impl
def test_round_trip(spec, tmp_path):
    d = _built(spec)
    inp = tmp_path / "in.bin"
    inp.write_bytes(_packets(5))  # 5 x 90-byte packets
    pkt = tmp_path / "out.pkt"
    assert adapter.compress(d, SIMPLE, inp, pkt).ok
    assert pkt.exists()

    depkt = tmp_path / "out.depkt"
    assert adapter.decompress(d, SIMPLE, pkt, depkt).ok
    assert depkt.read_bytes() == inp.read_bytes()


@impl
def test_reference_byte_identity(spec, tmp_path):
    """Every adapter reports reference_conformant, so every one must match the committed .pkt."""
    src = CCSDS124 / "test-vectors" / "input" / "simple.bin"
    expected = CCSDS124 / "test-vectors" / "expected-output" / "simple.bin.pkt"
    if not (src.exists() and expected.exists()):
        pytest.skip("ccsds124 test-vectors not present")
    out = tmp_path / "simple.pkt"
    assert adapter.compress(_built(spec), SIMPLE, src, out).ok
    assert out.read_bytes() == expected.read_bytes()


@impl
def test_sub_byte_f(spec, tmp_path):
    """F=1 bit: each packet is a byte-padded 1-byte stride (bit 7 = data, bits 6..0 = padding).

    An impl whose codec API is byte-aligned only must reject it cleanly rather than mishandle it.
    """
    d = _built(spec)
    inp = tmp_path / "in.bin"
    inp.write_bytes(bytes(0x80 if (i % 16) < 3 else 0x00 for i in range(256)))
    params = Params(packet_bits=1, pt=8, ft=16, rt=32, robustness=1)
    pkt = tmp_path / "out.pkt"
    result = adapter.compress(d, params, inp, pkt)

    if not spec.sub_byte_f:
        assert result.ok is False
        return
    assert result.ok
    depkt = tmp_path / "out.depkt"
    assert adapter.decompress(d, params, pkt, depkt).ok
    assert depkt.read_bytes() == inp.read_bytes()


@impl
def test_unsupported_packet_bits_rejected(spec, tmp_path):
    """A byte-aligned F the adapter cannot handle is rejected, not silently mishandled."""
    if spec.unsupported_packet_bits is None:
        pytest.skip(f"{spec.name} supports every byte-aligned F")
    inp = tmp_path / "in.bin"
    inp.write_bytes(bytes(64))
    params = Params(packet_bits=spec.unsupported_packet_bits, pt=10, ft=20, rt=50, robustness=1)
    assert adapter.compress(_built(spec), params, inp, tmp_path / "out.pkt").ok is False


# --- bench --------------------------------------------------------------------------------------


@impl
def test_bench_payload(spec, tmp_path):
    d = _built(spec)
    inp = tmp_path / "in.bin"
    inp.write_bytes(_packets(4, step=3))  # 4 x 90-byte packets

    payload = adapter.bench(d, "compress", inp, SIMPLE, warmup=2, iterations=10)
    assert payload.op == "compress"
    assert payload.iterations == 10
    assert payload.packets_per_iter == 4
    assert len(payload.nanos) == 10
    assert all(isinstance(n, int) and n >= 0 for n in payload.nanos)

    # to_metrics reuses benchmark.derive; sanity-check it produces a positive rate.
    m = adapter.to_metrics(payload, impl_name=spec.name, vector="in", packet_bits=720)
    assert m.num_packets == 4
    assert m.packets_per_sec > 0

    dpayload = adapter.bench(d, "decompress", inp, SIMPLE, warmup=1, iterations=5)
    assert dpayload.op == "decompress"
    assert dpayload.packets_per_iter == 4
    assert len(dpayload.nanos) == 5


# --- conformance --------------------------------------------------------------------------------


@impl
def test_conformance_compress_matches_uab(spec, tmp_path):
    if not spec.conformance:
        pytest.skip(f"{spec.name} reports no encoder conformance")
    vec, expected = _uab_vector("encoder", "encoder_sequence_0000", "raw+config", "124")
    out = tmp_path / "out.124"
    assert adapter.conformance_compress(_built(spec), vec, out).ok
    assert out.read_bytes() == expected.read_bytes()


@impl
def test_conformance_decompress_matches_uab(spec, tmp_path):
    if not spec.conformance:
        pytest.skip(f"{spec.name} reports no decoder conformance")
    vec, expected = _uab_vector("decoder", "decoder_sequence_00000", "124+config", "raw+large_f")
    out = tmp_path / "out.raw+large_f"
    assert adapter.conformance_decompress(_built(spec), vec, out).ok
    assert out.read_bytes() == expected.read_bytes()


@impl
def test_conformance_unsupported_exits_nonzero(spec, tmp_path):
    """An impl reporting no conformance must refuse the verbs, not pretend to run them."""
    if spec.conformance:
        pytest.skip(f"{spec.name} supports conformance")
    d = _built(spec)
    dummy = tmp_path / "x.bin"
    dummy.write_bytes(bytes(90))
    out = tmp_path / "out"
    assert adapter.conformance_compress(d, dummy, out).ok is False
    assert adapter.conformance_decompress(d, dummy, out).ok is False


# --- the shared contract assertion --------------------------------------------------------------


@impl
def test_conforms_to_contract(spec, tmp_path):
    # 720 bits is the one size reference-cpp's compile-time SUPPORTED_SIZES covers.
    assert_adapter_conforms(_built(spec), tmp_path=tmp_path, packet_bits=720)
