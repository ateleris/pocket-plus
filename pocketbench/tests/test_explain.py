"""Tests for explain: frame parsing, diffing, rendering, and the api facade.

All pure-logic tests build streams with `_stream`, mirroring the real decoder output format:
a sequence of frames (status byte + large_f bits padded to the byte for status 0x00), then a
32-bit big-endian large_f trailer.
"""

from pathlib import Path

import pytest

from pocketbench.explain import (
    STATUS_DECODED,
    STATUS_LOST,
    STATUS_UNDECODABLE,
    build_result,
    diff_bytes,
    diff_decoder,
    parse_frames,
    render_frame,
    resolve_vector,
    summary_lines,
)

REPO = Path(__file__).resolve().parents[1]


def _stream(frames, large_f):
    """Build a decoder output stream from (status, payload_bytes) pairs."""
    stride = (large_f + 7) // 8
    out = bytearray()
    for status, payload in frames:
        out.append(status)
        if status == STATUS_DECODED:
            out += payload.ljust(stride, b"\x00")[:stride]
    out += large_f.to_bytes(4, "big")
    return bytes(out)


# --------------------------------------------------------------------------- parsing


def test_parse_all_decoded_frames_consumes_exactly():
    data = _stream([(STATUS_DECODED, b"\xaa\xbb")] * 3, large_f=16)
    p = parse_frames(data)
    assert p.large_f == 16 and p.exact
    assert [f.status for f in p.frames] == [STATUS_DECODED] * 3
    assert p.frames[0].payload == b"\xaa\xbb" and p.frames[0].payload_bits == 16


def test_parse_mixed_statuses_only_decoded_carry_payload():
    data = _stream(
        [(STATUS_DECODED, b"\x01\x02"), (STATUS_UNDECODABLE, b""), (STATUS_LOST, b"")],
        large_f=16,
    )
    p = parse_frames(data)
    assert p.exact and [f.status for f in p.frames] == [0x00, 0x01, 0x02]
    assert p.frames[1].payload == b"" and p.frames[1].payload_bits == 0
    assert p.frames[2].payload == b""


def test_parse_large_f_zero_has_no_payloads():
    data = _stream([(STATUS_UNDECODABLE, b"")] + [(STATUS_LOST, b"")] * 6, large_f=0)
    p = parse_frames(data)
    assert p.large_f == 0 and p.exact and len(p.frames) == 7


def test_parse_sub_byte_large_f_pads_to_byte():
    p = parse_frames(_stream([(STATUS_DECODED, b"\x80")], large_f=1))
    assert p.large_f == 1 and p.exact
    assert p.frames[0].payload == b"\x80" and p.frames[0].payload_bits == 1


def test_parse_truncated_payload_is_inexact():
    data = _stream([(STATUS_DECODED, b"\xaa\xbb")], large_f=16)
    p = parse_frames(data[:-5])   # drop payload bytes, keep a trailer-sized tail
    assert not p.exact


def test_parse_too_short_for_trailer_is_inexact():
    p = parse_frames(b"\x00\x01")
    assert p.large_f == 0 and p.frames == [] and not p.exact


# --------------------------------------------------------------------------- decoder diff


def test_identical_streams_all_frames_match():
    data = _stream([(STATUS_DECODED, b"\xaa\xbb")] * 4, large_f=16)
    d = diff_decoder(data, data)
    assert d.frames_total == 4 and d.frames_matching == 4
    assert d.frame_diffs == [] and d.first_divergence is None
    assert not d.frame_count_differs and d.parse_reliable


def test_large_f_divergence_stops_frame_parsing():
    exp = _stream([(STATUS_DECODED, b"\xaa\xbb")] * 2, large_f=16)
    act = _stream([(STATUS_DECODED, b"\xaa\xbb")] * 2, large_f=24)
    d = diff_decoder(exp, act)
    assert d.f_differs and not d.parse_reliable
    assert d.expected_f == 16 and d.actual_f == 24
    assert d.frame_diffs == []          # no misleading frame findings


def test_payload_only_diffs_report_every_frame_without_cascade():
    exp = _stream([(STATUS_DECODED, b"\x00\x00")] * 3, large_f=16)
    act = _stream(
        [(STATUS_DECODED, b"\x00\x00"), (STATUS_DECODED, b"\x01\x00"),
         (STATUS_DECODED, b"\x00\x80")],
        large_f=16,
    )
    d = diff_decoder(exp, act)
    assert [fd.index for fd in d.frame_diffs] == [1, 2]
    assert not d.frame_count_differs
    assert d.first_divergence == 1
    assert d.frames_matching == 1 and d.frames_total == 3
    assert not any(fd.status_differs for fd in d.frame_diffs)


def test_payload_diff_bit_indices_are_msb_first():
    exp = _stream([(STATUS_DECODED, b"\x00\x00")], large_f=16)
    act = _stream([(STATUS_DECODED, b"\x01\x80")], large_f=16)
    (fd,) = diff_decoder(exp, act).frame_diffs
    # 0x01 differs in bit 7 of byte 0; 0x80 differs in bit 0 of byte 1 -> bit 8.
    assert fd.first_diff_bit == 7 and fd.last_diff_bit == 8


def test_status_divergence_does_not_desync_later_frames():
    """The format is self-delimiting, so one wrong status does not cascade.

    Frame 1's status differs and its payload is absent, which shifts every later byte offset. The
    frame parse re-synchronizes immediately, so frames 2-4 still match and only frame 1 is reported.
    """
    exp = _stream([(STATUS_DECODED, b"\xaa\xbb")] * 5, large_f=16)
    act = _stream(
        [(STATUS_DECODED, b"\xaa\xbb"), (STATUS_UNDECODABLE, b"")]
        + [(STATUS_DECODED, b"\xaa\xbb")] * 3,
        large_f=16,
    )
    d = diff_decoder(exp, act)
    assert [fd.index for fd in d.frame_diffs] == [1]
    assert d.frame_diffs[0].status_differs
    assert d.first_divergence == 1
    assert d.frames_total == 5 and d.actual_frames == 5
    assert not d.frame_count_differs
    assert d.frames_matching == 4      # 0, 2, 3, 4 all still line up
    assert len(act) < len(exp)         # byte offsets did shift


def test_multiple_status_divergences_are_all_reported():
    exp = _stream([(STATUS_DECODED, b"\xaa\xbb")] * 4, large_f=16)
    act = _stream(
        [(STATUS_DECODED, b"\xaa\xbb"), (STATUS_UNDECODABLE, b""),
         (STATUS_DECODED, b"\xaa\xbb"), (STATUS_LOST, b"")],
        large_f=16,
    )
    d = diff_decoder(exp, act)
    assert [fd.index for fd in d.frame_diffs] == [1, 3]
    assert all(fd.status_differs for fd in d.frame_diffs)


def test_actual_shorter_than_expected_flags_frame_count():
    exp = _stream([(STATUS_DECODED, b"\xaa\xbb")] * 3, large_f=16)
    act = _stream([(STATUS_DECODED, b"\xaa\xbb")] * 2, large_f=16)
    d = diff_decoder(exp, act)
    (fd,) = d.frame_diffs
    assert fd.index == 2 and fd.actual_status is None and fd.status_differs
    assert d.frame_count_differs and d.actual_frames == 2
    text = "\n".join(summary_lines(_result_for(exp, act)))
    assert "frame count  3 -> 2  MISMATCH" in text
    assert "ends early" in text


def _result_for(exp, act):
    return build_result(
        impl="mock", vector="decoder_sequence_00000.raw+large_f", phase="decoder",
        ran=True, returncode=0, stderr="", expected=exp, actual=act,
    )


def test_summary_caps_listed_frames_but_keeps_all_data():
    exp = _stream([(STATUS_DECODED, b"\x00\x00")] * 30, large_f=16)
    act = _stream([(STATUS_DECODED, b"\xff\x00")] * 30, large_f=16)
    r = _result_for(exp, act)
    assert len(r.decoder.frame_diffs) == 30          # full data retained
    text = "\n".join(summary_lines(r, max_frames=5))
    assert "... 25 more diverging frames" in text
    assert "0 of 30 frames match" in text


# --------------------------------------------------------------------------- byte diff


def test_diff_bytes_finds_first_offset():
    bd = diff_bytes(b"abcdef", b"abXdef")
    assert bd.first_diff_offset == 2
    assert bd.expected_byte == ord("c") and bd.actual_byte == ord("X")
    assert bd.size_delta == 0


def test_diff_bytes_prefix_has_no_differing_byte():
    bd = diff_bytes(b"abcdef", b"abc")
    assert bd.first_diff_offset is None
    assert bd.size_delta == -3


# --------------------------------------------------------------------------- rendering


def _pair(exp_payload, act_payload, large_f=16):
    e = parse_frames(_stream([(STATUS_DECODED, exp_payload)], large_f)).frames[0]
    a = parse_frames(_stream([(STATUS_DECODED, act_payload)], large_f)).frames[0]
    return e, a


def test_render_hex_marks_the_differing_byte():
    e, a = _pair(b"\x00\x00", b"\x00\xff")
    out = render_frame(e, a, mode="hex")
    assert "exp  00 00" in out and "act  00 ff" in out
    exp_line = next(ln for ln in out.splitlines() if "exp  00 00" in ln)
    mark_line = next(ln for ln in out.splitlines() if "^^" in ln)
    assert mark_line.index("^^") == exp_line.index("00 00") + 3   # under the second byte


def test_render_bin_marks_the_differing_bit():
    e, a = _pair(b"\x00", b"\x01", large_f=8)
    out = render_frame(e, a, mode="bin")
    assert "exp  00000000" in out and "act  00000001" in out
    exp_line = next(ln for ln in out.splitlines() if "exp  0000" in ln)
    mark_line = next(ln for ln in out.splitlines() if "^" in ln)
    assert mark_line.index("^") == exp_line.index("00000000") + 7


def test_render_identical_payload_says_so():
    e, a = _pair(b"\xaa\xbb", b"\xaa\xbb")
    assert "payload identical" in render_frame(e, a)


def test_render_status_mismatch_skips_byte_comparison():
    e = parse_frames(_stream([(STATUS_DECODED, b"\xaa\xbb")], 16)).frames[0]
    a = parse_frames(_stream([(STATUS_UNDECODABLE, b"")], 16)).frames[0]
    out = render_frame(e, a)
    assert "MISMATCH" in out
    assert "no byte comparison" in out
    assert "you rejected a packet the reference decoded" in out


def test_render_only_differing_lines_unless_full():
    payload = bytes(64)
    changed = bytearray(payload)
    changed[40] = 0xFF
    e, a = _pair(bytes(payload), bytes(changed), large_f=512)
    trimmed = render_frame(e, a, mode="hex", context=0)
    assert "0x0020" in trimmed and "0x0000" not in trimmed
    # 64 bytes at 16 per line = 4 rows ("exp  ", distinct from the "expected" status line).
    assert render_frame(e, a, mode="hex", full=True).count("exp  ") == 4


def test_render_rejects_bad_mode():
    e, a = _pair(b"\x00", b"\x01", large_f=8)
    with pytest.raises(ValueError, match="mode must be"):
        render_frame(e, a, mode="octal")


# --------------------------------------------------------------------------- result + show_frame


def _result(exp, act, *, phase="decoder", ran=True, returncode=0, stderr=""):
    return build_result(
        impl="mock", vector="decoder_sequence_00000.raw+large_f", phase=phase,
        ran=ran, returncode=returncode, stderr=stderr, expected=exp, actual=act,
    )


def test_result_identical_when_bytes_match():
    data = _stream([(STATUS_DECODED, b"\xaa\xbb")] * 2, large_f=16)
    r = _result(data, data)
    assert r.identical
    assert "identical to the reference output" in "\n".join(summary_lines(r))


def test_result_not_ran_reports_adapter_failure():
    data = _stream([(STATUS_DECODED, b"\xaa\xbb")], large_f=16)
    r = _result(data, b"", ran=False, returncode=101, stderr="boom\nmore")
    assert r.decoder is None
    text = "\n".join(summary_lines(r))
    assert "adapter did not produce output (exit 101)" in text
    assert "boom" in text


def test_show_frame_does_not_warn_when_frame_counts_agree():
    """A status divergence alone is not a reason to distrust later frames."""
    exp = _stream([(STATUS_DECODED, b"\x00\x00")] * 4, large_f=16)
    act = _stream(
        [(STATUS_DECODED, b"\x00\x00"), (STATUS_UNDECODABLE, b"")]
        + [(STATUS_DECODED, b"\x00\x00")] * 2,
        large_f=16,
    )
    r = _result(exp, act)
    assert "WARNING" not in r.show_frame(0)
    assert "WARNING" not in r.show_frame(3)


def test_show_frame_warns_past_a_frame_count_mismatch():
    exp = _stream([(STATUS_DECODED, b"\x00\x00")] * 4, large_f=16)
    act = _stream([(STATUS_DECODED, b"\x00\x00")] * 2, large_f=16)
    r = _result(exp, act)
    assert "WARNING" not in r.show_frame(1)
    assert "frame counts differ" in r.show_frame(2)


def test_show_frame_rejects_encoder_phase():
    r = _result(b"\x01\x02\x03", b"\x01\x02\x04", phase="encoder")
    with pytest.raises(ValueError, match="decoder-only"):
        r.show_frame(0)


def test_show_frame_rejects_unreliable_parse():
    exp = _stream([(STATUS_DECODED, b"\xaa\xbb")], large_f=16)
    act = _stream([(STATUS_DECODED, b"\xaa\xbb")], large_f=24)
    with pytest.raises(ValueError, match="large_f differs"):
        _result(exp, act).show_frame(0)


def test_show_frame_rejects_out_of_range():
    data = _stream([(STATUS_DECODED, b"\xaa\xbb")] * 2, large_f=16)
    with pytest.raises(ValueError, match="out of range"):
        _result(data, data).show_frame(9)


def test_encoder_result_says_no_packet_attribution():
    r = _result(b"\x01\x02\x03", b"\x01\x02\x04", phase="encoder")
    text = "\n".join(summary_lines(r))
    assert "first differing byte  2" in text
    assert "no packet attribution" in text


def test_empty_expected_output_is_reported():
    r = _result(b"", b"\x01\x02", phase="encoder")
    assert "expected empty output, got 2 B" in "\n".join(summary_lines(r))


# --------------------------------------------------------------------------- resolution


def _suite(tmp_path):
    for sub in ("encoder_input", "encoder_output", "decoder_input", "decoder_output"):
        (tmp_path / sub).mkdir()
    (tmp_path / "decoder_input" / "decoder_sequence_00042.124+config").write_bytes(b"in")
    (tmp_path / "decoder_output" / "decoder_sequence_00042.raw+large_f").write_bytes(b"out")
    (tmp_path / "encoder_input" / "encoder_sequence_0007.raw+config").write_bytes(b"in")
    return tmp_path


@pytest.mark.parametrize("name", [
    "decoder_sequence_00042",
    "decoder_sequence_00042.raw+large_f",   # as ConformanceResult.failures records it
    "decoder_sequence_00042.124+config",    # the input name
])
def test_resolve_accepts_stem_and_either_extension(tmp_path, name):
    v = resolve_vector(_suite(tmp_path), name)
    assert v.stem == "decoder_sequence_00042"
    assert v.phase == "decoder" and v.verb == "conformance-decompress"
    assert v.name == "decoder_sequence_00042.raw+large_f"
    assert v.input_path.exists() and v.expected_path.exists()


def test_resolve_encoder_phase_and_verb(tmp_path):
    v = resolve_vector(_suite(tmp_path), "encoder_sequence_0007")
    assert v.phase == "encoder" and v.verb == "conformance-compress"
    assert v.name == "encoder_sequence_0007.124"


def test_resolve_unknown_phase_raises(tmp_path):
    with pytest.raises(LookupError, match="cannot tell the phase"):
        resolve_vector(_suite(tmp_path), "sequence_0001")


def test_resolve_missing_vector_raises(tmp_path):
    with pytest.raises(LookupError, match="no such vector"):
        resolve_vector(_suite(tmp_path), "decoder_sequence_99999")


# --------------------------------------------------------------------------- api facade


def _suite_with_vector(tmp_path, payload=b"HELLO"):
    """A one-vector synthetic suite the mock adapter (which echoes input) can round-trip."""
    data = tmp_path / "suite"
    for sub in ("decoder_input", "decoder_output"):
        (data / sub).mkdir(parents=True)
    (data / "decoder_input" / "decoder_sequence_00001.124+config").write_bytes(payload)
    (data / "decoder_output" / "decoder_sequence_00001.raw+large_f").write_bytes(payload)
    return data


def _pb(tmp_path, data_dir):
    from pocketbench.adapter.discovery import discover_adapters
    from pocketbench.api import PocketBench
    from pocketbench.config import Config, ConformanceSuite, Settings

    found = discover_adapters(REPO / "adapters", repo_root=REPO, variables={})
    mock = next(d for d in found if d.manifest.name == "mock")
    cfg = Config(
        settings=Settings(ccsds124_root=tmp_path, results_dir=tmp_path / "results"),
        datasets={},
        adapters=[mock],
        conformance_suite=ConformanceSuite(
            name="synthetic", data_dir=data_dir, manifest=data_dir / "file_list.csv"
        ),
    )
    return PocketBench(cfg)


def test_api_explain_reports_identical_when_output_matches(tmp_path):
    data = _suite_with_vector(tmp_path)
    run = _pb(tmp_path, data).explain("decoder_sequence_00001", build=False)
    (r,) = run.results
    assert r.impl == "mock" and r.phase == "decoder"
    assert r.vector == "decoder_sequence_00001.raw+large_f"
    assert r.ran and r.identical


def test_api_explain_reports_divergence(tmp_path):
    data = _suite_with_vector(tmp_path)
    # Doctor the ground truth so the mock's echo no longer matches it.
    (data / "decoder_output" / "decoder_sequence_00001.raw+large_f").write_bytes(b"HELLP")
    run = _pb(tmp_path, data).explain("decoder_sequence_00001.raw+large_f", build=False)
    (r,) = run.results
    assert r.ran and not r.identical
    assert r.byte_diff.first_diff_offset == 4
    assert "expected 5 B, got 5 B" in run.summary()


def test_api_explain_unknown_vector_raises(tmp_path):
    from pocketbench.api import VectorNotFound

    data = _suite_with_vector(tmp_path)
    with pytest.raises(VectorNotFound, match="no such vector"):
        _pb(tmp_path, data).explain("decoder_sequence_77777", build=False)


def test_api_explain_missing_expected_output_raises(tmp_path):
    from pocketbench.api import VectorNotFound

    data = _suite_with_vector(tmp_path)
    (data / "decoder_output" / "decoder_sequence_00001.raw+large_f").unlink()
    with pytest.raises(VectorNotFound, match="no expected output"):
        _pb(tmp_path, data).explain("decoder_sequence_00001", build=False)


def test_api_explain_accepts_several_vectors(tmp_path):
    data = _suite_with_vector(tmp_path)
    (data / "decoder_input" / "decoder_sequence_00002.124+config").write_bytes(b"WORLD")
    (data / "decoder_output" / "decoder_sequence_00002.raw+large_f").write_bytes(b"WORLD")
    run = _pb(tmp_path, data).explain(
        ["decoder_sequence_00001", "decoder_sequence_00002"], build=False
    )
    assert [r.vector for r in run.results] == [
        "decoder_sequence_00001.raw+large_f",
        "decoder_sequence_00002.raw+large_f",
    ]
    assert all(r.identical for r in run)
