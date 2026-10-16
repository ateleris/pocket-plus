"""Every real adapter, driven over the contract from one declarative table.

One test body per behaviour, parametrized over ADAPTERS: adding an adapter means adding a row, and
it then inherits the whole suite. Every test skips, never fails, when the codec is absent.
"""

from __future__ import annotations

import tomllib
from dataclasses import dataclass
from pathlib import Path

import pytest

from pocketbench import adapter, conformance, orchestrate, records, sweep
from pocketbench.config import Dataset
from pocketbench.orchestrate import Prepared
from tests.adapter_contract import assert_rejects_contract_violations
from tests.conftest import built_adapter, ccsds124_root

REPO = Path(__file__).resolve().parents[1]
CCSDS124 = ccsds124_root()
NOT_REPRESENTABLE = "stopped: input not representable by this codec's API"


@dataclass(frozen=True)
class Spec:
    """What is impl-specific about one adapter. Everything else is the shared contract."""

    name: str
    build_profile: str          # substring of the self-reported build_profile
    limitations: str            # substring of the self-reported limitations
    sub_byte_f: bool            # its codec API takes an F that is not a multiple of 8
    uab_encoder_matches: bool   # its output for encoder_sequence_0006 equals the suite's
    uab_decoder_matches: bool   # its output for decoder_sequence_00000 equals the suite's
    # A byte-aligned F its codec API cannot take (reference-cpp instantiates its packet length at
    # compile time); None when every byte-aligned F is representable.
    unsupported_packet_bits: int | None = None
    # Its codec's own error at the production packet size; None when it round-trips there.
    production_error: str | None = None


ADAPTERS = [
    Spec("reference-c", "-O3", "CCSDS124_MAX_PACKET_LENGTH=", sub_byte_f=True,
         uab_encoder_matches=True, uab_decoder_matches=True),
    Spec("reference-cpp", "-O3", "720-bit", sub_byte_f=False,
         uab_encoder_matches=False, uab_decoder_matches=False, unsupported_packet_bits=512,
         # count_encode emits the run-length suffix in one append_value capped at 24 bits, so a
         # zero run of 32769 bits or more is InvalidArg.
         production_error="stopped: codec error -1"),
    Spec("pocketrust-u32", "32-bit blocks", "65535", sub_byte_f=True,
         uab_encoder_matches=True, uab_decoder_matches=True),
    Spec("pocketrust-u64", "64-bit blocks", "65535", sub_byte_f=True,
         uab_encoder_matches=True, uab_decoder_matches=True),
    Spec("pocketplus", "-O3", "bool-per-bit", sub_byte_f=True,
         uab_encoder_matches=True, uab_decoder_matches=False),
]

# Named ids so `-k reference-c` selects one adapter's rows.
impl = pytest.mark.parametrize("spec", ADAPTERS, ids=[s.name for s in ADAPTERS])


def _built(spec: Spec):
    return built_adapter(spec.name)


def _prepared(spec: Spec) -> Prepared:
    d = _built(spec)
    return Prepared(adapter=d, capabilities=adapter.capabilities(d))


def _packets(count: int, step: int = 7, packet_bytes: int = 90) -> bytes:
    return bytes((i * step) % 256 for i in range(packet_bytes * count))


def _dataset(tmp_path: Path, data: bytes, *, packet_bits: int = 720, pt: int = 10, ft: int = 20,
             rt: int = 50, robustness: int = 1) -> Dataset:
    inp = tmp_path / "in.bin"
    inp.write_bytes(data)
    return Dataset(name="t", input=inp, expected=None, packet_bits=packet_bits,
                   pt=pt, ft=ft, rt=rt, robustness=robustness)


def _compress(d, ds: Dataset, tmp_path: Path):
    data, _ = orchestrate.dataset_input(ds)
    inp = tmp_path / "in.rec"
    inp.write_bytes(data)
    return adapter.run_verb(d, "compress", inp, tmp_path / "packets.rec")


def _round_trip(d, ds: Dataset, tmp_path: Path) -> bytes:
    comp = _compress(d, ds, tmp_path)
    assert comp.completed, comp.reason
    dec = adapter.run_verb(d, "decompress", tmp_path / "packets.rec", tmp_path / "fields.rec")
    assert dec.completed, dec.reason
    assert all(r.kind == records.STATUS_DECODED for r in dec.output.records)
    return b"".join(r.payload for r in dec.output.records)


def _conformance_dir() -> Path | None:
    cfg = REPO / "config" / "config.toml"
    if not cfg.exists():
        return None
    raw = tomllib.loads(cfg.read_text()).get("settings", {}).get("conformance_data_dir")
    return Path(raw) if raw else None


def _uab_vector(kind: str, stem: str, in_ext: str, out_ext: str) -> tuple[Path, Path]:
    """One UAB/CNES vector plus its expected output, or skip if the suite is not present."""
    d = _conformance_dir()
    if not d or not (d / f"{kind}_input").is_dir():
        pytest.skip(f"conformance_data_dir / {kind}_input not present")
    vec = d / f"{kind}_input" / f"{stem}.{in_ext}"
    expected = d / f"{kind}_output" / f"{stem}.{out_ext}"
    if not (vec.exists() and expected.exists()):
        pytest.skip(f"UAB {kind} vector {stem} not present")
    return vec, expected


@impl
def test_capabilities(spec):
    caps = adapter.capabilities(_built(spec))
    assert spec.build_profile in caps.build_profile
    assert spec.limitations in caps.limitations


@impl
def test_round_trip(spec, tmp_path):
    ds = _dataset(tmp_path, _packets(5))
    assert _round_trip(_built(spec), ds, tmp_path) == ds.input.read_bytes()


@impl
def test_reference_byte_identity(spec, tmp_path):
    src = CCSDS124 / "test-vectors" / "input" / "simple.bin"
    expected = CCSDS124 / "test-vectors" / "expected-output" / "simple.bin.pkt"
    if not (src.exists() and expected.exists()):
        pytest.skip("ccsds124 test-vectors not present")
    ds = Dataset(name="simple", input=src, expected=None, packet_bits=720,
                 pt=10, ft=20, rt=50, robustness=1)
    run = _compress(_built(spec), ds, tmp_path)
    assert run.completed, run.reason
    assert records.to_124(run.output) == expected.read_bytes()


@impl
def test_sub_byte_f(spec, tmp_path):
    """F=1 bit: each packet is a byte-padded 1-byte stride (bit 7 = data, bits 6..0 = padding)."""
    ds = _dataset(tmp_path, bytes(0x80 if (i % 16) < 3 else 0x00 for i in range(256)),
                  packet_bits=1, pt=8, ft=16, rt=32, robustness=1)
    d = _built(spec)
    if not spec.sub_byte_f:
        assert _compress(d, ds, tmp_path).reason == NOT_REPRESENTABLE
        return
    assert _round_trip(d, ds, tmp_path) == ds.input.read_bytes()


@impl
def test_unsupported_packet_bits_not_representable(spec, tmp_path):
    if spec.unsupported_packet_bits is None:
        pytest.skip(f"{spec.name} takes every byte-aligned F")
    ds = _dataset(tmp_path, bytes(64), packet_bits=spec.unsupported_packet_bits)
    assert _compress(_built(spec), ds, tmp_path).reason == NOT_REPRESENTABLE


@impl
def test_partial_last_field_is_not_representable(spec, tmp_path):
    run = _compress(_built(spec), _dataset(tmp_path, _packets(2) + b"\x01\x02"), tmp_path)
    assert run.reason == NOT_REPRESENTABLE
    assert run.summary["records_out"] == 2


@impl
def test_bench_payload(spec, tmp_path):
    metrics = orchestrate.bench_metrics(
        _prepared(spec), _dataset(tmp_path, _packets(4, step=3)), warmup=2, iterations=10
    )
    assert [m.operation for m in metrics] == ["compress", "decompress"]
    assert all(m.num_packets == 4 and m.packets_per_sec > 0 for m in metrics)


@impl
def test_uab_encoder_vector(spec, tmp_path):
    vec, expected = _uab_vector("encoder", "encoder_sequence_0006", "raw+config", "124")
    vr = conformance.run_vector(_prepared(spec), "encoder", vec, tmp_path)
    assert (vr.data == expected.read_bytes()) is spec.uab_encoder_matches, vr.run.reason


@impl
def test_uab_decoder_vector(spec, tmp_path):
    vec, expected = _uab_vector("decoder", "decoder_sequence_00000", "124+config", "raw+large_f")
    vr = conformance.run_vector(_prepared(spec), "decoder", vec, tmp_path)
    assert (vr.data == expected.read_bytes()) is spec.uab_decoder_matches, vr.run.reason


@impl
def test_rejects_contract_violations(spec, tmp_path):
    assert_rejects_contract_violations(_built(spec), tmp_path=tmp_path)


def test_reference_c_lets_the_codec_judge_a_discovered_f_above_its_cap(tmp_path):
    """A 720-bit build discovering F = 1376 gets the same treatment as F = 1376 in the header: the
    codec's own decompressor_init rejects it, rather than the adapter deciding F is unknown."""
    vec, _ = _uab_vector("decoder", "decoder_sequence_00000", "124+config", "raw+large_f")
    d = built_adapter("reference-c")
    try:
        assert adapter.build(d, max_packet_bits=720).ok
        p = Prepared(adapter=d, capabilities=adapter.capabilities(d))
        run = conformance.run_vector(p, "decoder", vec, tmp_path).run
        assert run.summary["stopped_at"] == 0
        assert run.reason.startswith("stopped: codec error")
    finally:
        assert adapter.build(d).ok


PRODUCTION_PACKET_BYTES = 4114


@impl
def test_round_trip_at_the_production_packet_size(spec, tmp_path):
    ds = _dataset(tmp_path, _packets(3, packet_bytes=PRODUCTION_PACKET_BYTES),
                  packet_bits=8 * PRODUCTION_PACKET_BYTES)
    if spec.production_error is not None:
        assert _compress(_built(spec), ds, tmp_path).reason == spec.production_error
    else:
        assert _round_trip(_built(spec), ds, tmp_path) == ds.input.read_bytes()


def test_conformant_encoders_sweep_to_identical_bits():
    src = CCSDS124 / "test-vectors" / "input" / "simple.bin"
    if not src.exists():
        pytest.skip("ccsds124 test-vectors not present")
    ds = Dataset(name="simple", input=src, packet_bits=720, pt=10, ft=20, rt=50, robustness=1)
    encoders = [_prepared(s) for s in ADAPTERS if s.uab_encoder_matches]
    results = sweep.run(encoders, [ds], sweep.expand(ft=[None, 20], robustness=[0, 1, 2]))
    assert all(r.reason is None for r in results)
    by_config: dict[tuple, set[int]] = {}
    for r in results:
        by_config.setdefault((r.ft, r.robustness), set()).add(r.output_bits)
    assert len(by_config) == 6
    assert all(len(bits) == 1 for bits in by_config.values()), by_config
