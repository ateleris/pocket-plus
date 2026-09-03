"""Tests for the pure benchmark metric derivation."""

from pocketbench.benchmark import BenchRecord, derive


def _record(**kw) -> BenchRecord:
    base = dict(impl="c", vector="simple", operation="compress",
                us_per_iter=400.0, us_per_pkt=0.0, kbps=0.0, num_packets=100)
    base.update(kw)
    return BenchRecord(**base)


def test_derive_basic_metrics():
    m = derive(_record(us_per_iter=400.0, num_packets=100), packet_bits=720)
    assert m.num_packets == 100
    assert m.size_bytes == 100 * 90
    assert m.time_ms == 0.4  # 400 us / 1000
    assert m.us_per_pkt == 4.0  # 400 / 100
    assert m.packets_per_sec == 100 * 1_000_000 / 400.0


def test_derive_sub_byte_f_uses_byte_padded_stride():
    # F=1 bit: each packet occupies a byte-padded stride of ceil(1/8) = 1 byte, so throughput
    # counts 1 byte/packet (not 1 bit). F=9 rounds up to a 2-byte stride.
    assert derive(_record(num_packets=100), packet_bits=1).size_bytes == 100 * 1
    assert derive(_record(num_packets=100), packet_bits=9).size_bytes == 100 * 2


def test_derive_zero_time_is_safe():
    m = derive(_record(us_per_iter=0.0, num_packets=0), packet_bits=720)
    assert m.packets_per_sec == 0.0
    assert m.us_per_pkt == 0.0
    assert m.mb_per_sec == 0.0
