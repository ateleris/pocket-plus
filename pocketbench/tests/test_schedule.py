"""The pt/ft/rt schedule of the ccsds124 reference compressor, as flag bytes."""

from pocketbench import schedule


def test_flag_byte_layout():
    assert schedule.flag_byte(True, False, True, 3) == 0x53
    assert schedule.flag_byte(False, True, False, 7) == 0x27


def test_countdown_schedule_matches_the_reference():
    # Worked by hand from ccsds124 compress.c: packet 0 and t <= R self-initialise, and the three
    # counters advance on every packet after the first.
    got = schedule.flags(8, pt=2, ft=3, rt=4, robustness=1)
    assert got == [0x51, 0x51, 0x21, 0x41, 0x31, 0x01, 0x61, 0x01]


def test_manual_mode_when_any_period_is_not_positive():
    assert schedule.flags(3, pt=0, ft=20, rt=50, robustness=2) == [0x02, 0x02, 0x02]


def test_an_off_period_fires_only_on_the_first_packet():
    got = schedule.flags(6, pt=2, ft=None, rt=None, robustness=1)
    assert got == [0x51, 0x51, 0x21, 0x01, 0x21, 0x01]


def test_all_periods_off_leaves_only_the_first_packet_flagged():
    assert schedule.flags(4, pt=None, ft=None, rt=None, robustness=0) == [0x50, 0x00, 0x00, 0x00]


def test_a_zero_period_is_still_manual_mode_next_to_an_off_period():
    assert schedule.flags(2, pt=0, ft=None, rt=None, robustness=3) == [0x03, 0x03]
