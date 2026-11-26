"""Every real adapter fed input that breaks the standard: the codec must reject it or decode it
to a status, never crash, hang or write a malformed output."""

from __future__ import annotations

import random
from pathlib import Path

import pytest

from pocketbench import adapter, records
from pocketbench.records import Container, Record
from tests.test_adapters import NOT_REPRESENTABLE, Spec, _built, impl

CODEC_ERROR = "stopped: codec error"
FT, RT = 0x40, 0x10
F = 16


def _run(spec: Spec, verb: str, c: Container, tmp_path: Path) -> adapter.VerbRun:
    inp = tmp_path / f"{verb}.in"
    inp.write_bytes(records.encode(c))
    run = adapter.run_verb(_built(spec), verb, inp, tmp_path / f"{verb}.out", timeout=120)
    assert run.returncode == 0, f"{spec.name}: {run.reason}\n{run.stderr}"
    assert run.summary is not None and run.output is not None, f"{spec.name}: {run.reason}"
    return run


def _compress(spec: Spec, flags: list[int], tmp_path: Path, f: int = 720) -> adapter.VerbRun:
    stride = records.payload_bytes(f)
    fields = bytes((i * 37 + 11) % 256 for i in range(stride * len(flags)))
    return _run(spec, "compress", records.from_fields(f, fields, flags), tmp_path)


def _assert_invalid_flags_handled(spec: Spec, run: adapter.VerbRun, at: int) -> None:
    if spec.encodes_invalid_flags:
        assert run.completed, f"{spec.name}: {run.reason}"
        return
    _assert_codec_rejects(spec, run, at)


def _assert_codec_rejects(spec: Spec, run: adapter.VerbRun, at: int) -> None:
    """Stopped at record `at` by the codec itself, unless the adapter guards a spec's domain."""
    assert run.summary["stopped_at"] == at, f"{spec.name}: {run.reason}"
    assert len(run.output.records) == at - 1
    if spec.adapter_guards and run.reason == NOT_REPRESENTABLE:
        return
    assert run.reason.startswith(CODEC_ERROR), f"{spec.name}: {run.reason}"


@impl
@pytest.mark.parametrize("robustness", [8, 15])
def test_encoder_rejects_robustness_above_7(spec, robustness, tmp_path):
    run = _compress(spec, [FT | RT | robustness] * 3, tmp_path)
    _assert_invalid_flags_handled(spec, run, at=1)


@impl
@pytest.mark.parametrize("missing", [FT, RT], ids=["ft", "rt"])
def test_encoder_rejects_a_flag_cleared_while_t_is_within_robustness(spec, missing, tmp_path):
    """Blue book 3.3.2 c) and d): f_t = r_t = 1 for t <= R_t."""
    run = _compress(spec, [FT | RT | 2, FT | RT | 2, (FT | RT) & ~missing | 2], tmp_path)
    _assert_invalid_flags_handled(spec, run, at=3)


@impl
def test_encoder_rejects_f_zero(spec, tmp_path):
    run = _run(spec, "compress", Container(0, (Record(0, 0, b""), Record(FT | RT, 0, b""))),
               tmp_path)
    assert run.summary["stopped_at"] == 0, f"{spec.name}: {run.reason}"
    assert run.reason.startswith(CODEC_ERROR) or run.reason == NOT_REPRESENTABLE, run.reason


@impl
def test_encoder_rejects_f_above_65535(spec, tmp_path):
    """NOT_REPRESENTABLE is allowed: an API whose F type cannot hold 65536 cannot be handed it."""
    run = _compress(spec, [FT | RT | 1], tmp_path, f=65536)
    assert run.summary["stopped_at"] == 0, f"{spec.name}: {run.reason}"
    assert run.reason.startswith(CODEC_ERROR) or run.reason == NOT_REPRESENTABLE, run.reason


def _assert_decoded_cleanly(spec: Spec, run: adapter.VerbRun, sent: Container) -> None:
    """Every record before any stop has a status, and a decoded field is F bits long. Only the
    header F may be not representable, since a later stop would be the adapter judging a packet."""
    out = run.output
    stop = run.summary["stopped_at"]
    assert len(out.records) == (len(sent.records) if stop is None else stop), run.reason
    if stop is not None:
        api_limit = stop == 0 and run.reason == NOT_REPRESENTABLE
        assert api_limit or run.reason.startswith(CODEC_ERROR), f"{spec.name}: {run.reason}"
    for i, (got, rec) in enumerate(zip(out.records, sent.records)):
        if rec.kind & 1:
            assert got.kind == records.STATUS_LOST, f"{spec.name}: record {i}"
        else:
            assert got.kind in (records.STATUS_DECODED, records.STATUS_UNDECODABLE), \
                f"{spec.name}: record {i} has status {got.kind:#x}"
        if got.kind == records.STATUS_DECODED:
            assert out.f and got.bits == out.f, f"{spec.name}: record {i}: {got.bits} bits"
            assert len(got.payload) == records.payload_bytes(got.bits)
        else:
            assert got.bits == 0 and got.payload == b"", f"{spec.name}: record {i}"


def _decompress(spec: Spec, c: Container, tmp_path: Path) -> None:
    _assert_decoded_cleanly(spec, _run(spec, "decompress", c, tmp_path), c)


def _valid_packets(spec: Spec, tmp_path: Path, count: int = 6) -> list[Record]:
    run = _compress(spec, [FT | RT | 1, FT | RT | 1] + [1] * (count - 2), tmp_path)
    assert run.completed, f"{spec.name}: {run.reason}"
    return list(run.output.records)


@impl
@pytest.mark.parametrize("f", [0, F, 720], ids=["f-unknown", "f-16", "f-720"])
def test_decoder_handles_empty_packets(spec, f, tmp_path):
    _decompress(spec, Container(f, (Record(0, 0, b""),) * 3), tmp_path)


@impl
@pytest.mark.parametrize("f", [0, 720], ids=["f-unknown", "f-720"])
@pytest.mark.parametrize("declared", [10_000, 0xFFFF_FFFF])
def test_decoder_handles_a_declared_length_past_the_data(spec, f, declared, tmp_path):
    packets = _valid_packets(spec, tmp_path)
    last = Record(0, declared, packets[-1].payload)
    _decompress(spec, Container(f, (*packets[:-1], last)), tmp_path)


@impl
@pytest.mark.parametrize("f", [0, 720], ids=["f-unknown", "f-720"])
@pytest.mark.parametrize("cut", [1, 7, 64])
def test_decoder_handles_truncated_packets(spec, f, cut, tmp_path):
    cut_packets = []
    for p in _valid_packets(spec, tmp_path):
        bits = max(p.bits - cut, 0)
        cut_packets.append(Record(0, bits, p.payload[:records.payload_bytes(bits)]))
    _decompress(spec, Container(f, tuple(cut_packets)), tmp_path)


@impl
@pytest.mark.parametrize("f", [0, 720], ids=["f-unknown", "f-720"])
@pytest.mark.parametrize("extra", [1, 8, 100])
def test_decoder_handles_trailing_bits(spec, f, extra, tmp_path):
    long_packets = []
    for p in _valid_packets(spec, tmp_path):
        bits = p.bits + extra
        payload = (p.payload + b"\xa5" * records.payload_bytes(extra + 8))
        long_packets.append(Record(0, bits, payload[:records.payload_bytes(bits)]))
    _decompress(spec, Container(f, tuple(long_packets)), tmp_path)


@impl
@pytest.mark.parametrize("f", [0, 720], ids=["f-unknown", "f-720"])
@pytest.mark.parametrize("seed", range(4))
def test_decoder_handles_bit_flips(spec, f, seed, tmp_path):
    rng = random.Random(seed)
    flipped = []
    for p in _valid_packets(spec, tmp_path):
        data = bytearray(p.payload)
        for _ in range(1 + seed):
            i = rng.randrange(p.bits)
            data[i // 8] ^= 0x80 >> (i % 8)
        flipped.append(Record(0, p.bits, bytes(data)))
    _decompress(spec, Container(f, tuple(flipped)), tmp_path)


@impl
@pytest.mark.parametrize("seed", range(8))
def test_decoder_handles_random_bytes(spec, seed, tmp_path):
    rng = random.Random(seed)
    recs = []
    for _ in range(8):
        if rng.random() < 0.15:
            recs.append(Record(1, 0, b""))
            continue
        bits = rng.randrange(0, 2000)
        recs.append(Record(0, bits, rng.randbytes(records.payload_bytes(bits))))
    _decompress(spec, Container(rng.choice([0, F, 720, 1000]), tuple(recs)), tmp_path)


@impl
def test_decoder_handles_packets_for_a_different_f(spec, tmp_path):
    packets = _valid_packets(spec, tmp_path)
    _decompress(spec, Container(F, tuple(packets)), tmp_path)
