"""Tests for the yellow-book encoder input rules used to annotate conformance failures."""

from pocketbench.encoder_rules import (
    RULE_F,
    RULE_INIT,
    RULE_MASK_PADDING,
    RULE_PADDING,
    RULE_R,
    Violation,
    first_violation,
    outcome,
)


def _vector(f: int, packets: list[tuple[int, bytes]], mask: bytes | None = None) -> bytes:
    stride = (f + 7) // 8
    body = b"".join(bytes([flag]) + field for flag, field in packets)
    return f.to_bytes(4, "big") + (mask if mask is not None else bytes(stride)) + body


def test_a_valid_vector_breaks_no_rule():
    assert first_violation(_vector(16, [(0x10, b"AB"), (0x00, b"CD"), (0x80, b"EF")])) is None


def test_f_outside_the_standard_range_is_invalid_before_any_packet():
    assert first_violation(_vector(0, [])) == Violation(0, RULE_F)
    assert first_violation(_vector(65536, [(0x10, bytes(8192))])) == Violation(0, RULE_F)
    assert first_violation(_vector(65535, [(0x10, bytes(8192))])) is None


def test_nonzero_padding_in_the_initial_mask():
    assert first_violation(_vector(5, [(0x10, b"\x00")], mask=b"\x01")) == Violation(
        0, RULE_MASK_PADDING)


def test_r_above_7():
    assert first_violation(_vector(8, [(0x10, b"A"), (0x08, b"B")])) == Violation(1, RULE_R)


def test_init_phase_needs_r_and_with_r_above_0_also_f():
    assert first_violation(_vector(8, [(0x00, b"A")])) == Violation(0, RULE_INIT)
    assert first_violation(_vector(8, [(0x52, b"A"), (0x52, b"B"), (0x12, b"C")])) == Violation(
        2, RULE_INIT)
    assert first_violation(_vector(8, [(0x52, b"A"), (0x52, b"B"), (0x52, b"C"),
                                       (0x02, b"D")])) is None


def test_nonzero_padding_in_a_packet():
    assert first_violation(_vector(5, [(0x10, b"\x00"), (0x00, b"\x04")])) == Violation(
        1, RULE_PADDING)
    assert first_violation(_vector(5, [(0x10, b"\x00"), (0x00, b"\xf8")])) is None


def test_the_flag_byte_is_judged_before_the_padding_of_its_field():
    assert first_violation(_vector(5, [(0x10, b"\x00"), (0x08, b"\x07")])) == Violation(1, RULE_R)


def test_a_truncated_packet_breaks_no_rule_but_its_flag_byte_still_counts():
    assert first_violation(_vector(16, [(0x10, b"AB")]) + b"\x00C") is None
    assert first_violation(_vector(16, [(0x10, b"AB")]) + b"\x0fC") == Violation(1, RULE_R)
    assert first_violation(b"\x00\x00") is None


def test_outcome_compares_packets_emitted_with_the_invalid_packet():
    v = Violation(3, RULE_R)
    assert outcome(v, 4) == "codec encoded past it"
    assert outcome(v, 3) == "codec stopped there"
    assert outcome(v, 2) == "codec stopped at t=2, before it"
    assert outcome(v, None) == ""
