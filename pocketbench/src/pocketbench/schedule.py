"""The pt/ft/rt flag schedule of the ccsds124 reference compressor (``compress.c``), as flag bytes.

A flag byte is the yellow-book layout: bit 7 reserved, f (send mask), p (new mask), r
(uncompressed), then R in the low four bits.
"""

from __future__ import annotations


def flag_byte(send_mask: bool, new_mask: bool, uncompressed: bool, robustness: int) -> int:
    return (send_mask << 6) | (new_mask << 5) | (uncompressed << 4) | (robustness & 0x0F)


def _tick(period: int | None, counter: int | None) -> tuple[bool, int | None]:
    if period is None:
        return False, None
    return (True, period) if counter == 1 else (False, counter - 1)


def flags(
    count: int, *, pt: int | None, ft: int | None, rt: int | None, robustness: int
) -> list[int]:
    """Flag bytes for ``count`` packets. With any period <= 0 every flag stays 0 (manual mode). A
    period of None is off: that flag never fires after the first packet."""
    automatic = all(p is None or p > 0 for p in (pt, ft, rt))
    pt_c, ft_c, rt_c = pt, ft, rt
    out: list[int] = []
    for t in range(count):
        send = new = unc = False
        if automatic and t == 0:
            send = unc = True
        elif automatic:
            send, ft_c = _tick(ft, ft_c)
            new, pt_c = _tick(pt, pt_c)
            unc, rt_c = _tick(rt, rt_c)
            if t <= robustness:
                send, new, unc = True, False, True
        out.append(flag_byte(send, new, unc, robustness))
    return out
