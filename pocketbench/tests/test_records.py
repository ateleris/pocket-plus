"""The record container and its re-framing, which copy bytes and judge nothing."""

import struct

import pytest

from pocketbench import records
from pocketbench.records import Container, Record


def test_encode_decode_round_trip():
    c = Container(16, (Record(0, 16, b"\x00\x00"), Record(0x51, 16, b"\xab\xcd")))
    assert records.decode(records.encode(c)) == c


def test_encode_layout():
    c = Container(16, (Record(0x51, 12, b"\xab\xc0"),))
    assert records.encode(c) == b"\x00\x00\x00\x10" + b"\x51\x00\x00\x00\x0c" + b"\xab\xc0"


def test_headerless_container_is_empty_bytes():
    assert records.encode(Container(None)) == b""
    assert records.decode(b"\x00\x01") == Container(None)


def test_last_record_may_carry_a_short_payload():
    c = Container(16, (Record(0, 16, b"\x01\x02"), Record(0, 100, b"\x01")))
    assert records.decode(records.encode(c)) == c


def test_only_the_last_record_may_be_short():
    with pytest.raises(ValueError):
        records.encode(Container(16, (Record(0, 16, b"\x01"), Record(0, 16, b"\x02\x03"))))


def test_decode_rejects_a_cut_off_record_header():
    with pytest.raises(ValueError, match="truncated record header at byte 4"):
        records.decode(b"\x00\x00\x00\x10\x00\x00")


def test_from_raw_config_copies_every_field_unjudged():
    # Mask with non-zero padding, a flag byte with R=3 and r=0 at t=0, a field with non-zero
    # padding, then a cut-off field: all handed over exactly as they are.
    raw = struct.pack(">I", 12) + b"\xff\xff" + b"\x03" + b"\x12\x3f" + b"\x00" + b"\x45"
    assert records.from_raw_config(raw) == Container(12, (
        Record(0, 12, b"\xff\xff"),
        Record(0x03, 12, b"\x12\x3f"),
        Record(0x00, 12, b"\x45"),
    ))


def test_from_raw_config_without_a_header():
    assert records.from_raw_config(b"\x00\x01") == Container(None)


def test_from_raw_config_huge_f_stays_small():
    c = records.from_raw_config(b"\xff\xff\xff\xff" + b"\x00" * 3)
    assert c == Container(0xFFFFFFFF, (Record(0, 0xFFFFFFFF, b"\x00\x00\x00"),))
    assert len(records.encode(c)) == 4 + 5 + 3


def test_from_124_config_keeps_lost_zero_length_and_truncated_elements():
    raw = (
        b"\x00" + struct.pack(">I", 12) + b"\xab\xc0"      # received
        + b"\x01"                                          # lost
        + b"\x00" + struct.pack(">I", 0)                   # zero length, still handed over
        + b"\x00" + struct.pack(">I", 64) + b"\x01\x02"    # declares 64 bits, carries 16
    )
    assert records.from_124_config(raw) == Container(0, (
        Record(0, 12, b"\xab\xc0"),
        Record(1, 0, b""),
        Record(0, 0, b""),
        Record(0, 64, b"\x01\x02"),
    ))


def test_from_124_config_cut_off_length_field_is_an_empty_packet():
    assert records.from_124_config(b"\x00\x00\x00") == Container(0, (Record(0, 0, b""),))


def test_to_124_concatenates_the_packets():
    c = Container(16, (Record(0, 12, b"\xab\xc0"), Record(0, 3, b"\xe0")))
    assert records.to_124(c) == b"\xab\xc0\xe0"


def test_to_raw_large_f_writes_status_field_and_trailer():
    c = Container(12, (Record(0, 12, b"\xab\xc0"), Record(1, 0, b""), Record(2, 0, b"")))
    assert records.to_raw_large_f(c) == b"\x00\xab\xc0\x01\x02" + struct.pack(">I", 12)


def test_from_fields_builds_a_zero_mask_and_one_record_per_field():
    c = records.from_fields(12, b"\x01\x02\x03\x04\x05", [0x51, 0x01, 0x21])
    assert c == Container(12, (
        Record(0, 12, b"\x00\x00"),
        Record(0x51, 12, b"\x01\x02"),
        Record(0x01, 12, b"\x03\x04"),
        Record(0x21, 12, b"\x05"),
    ))
