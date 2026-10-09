//! Intermediate values of the last `compress` / `decompress` call.
use crate::decompressor::MAX_VT_HISTORY;
use crate::{Block, BUF_LEN};

/// Bits `start..start + len` of one compressed packet, counted from its first bit.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Span {
    pub start: usize,
    pub len: usize,
}

/// Output components in bitstream order.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Seg {
    RleX,
    Vt,
    Et,
    Kt,
    Ct,
    Dt,
    Qt,
    Ut,
}

pub const SEGS: [Seg; 8] = [Seg::RleX, Seg::Vt, Seg::Et, Seg::Kt, Seg::Ct, Seg::Dt, Seg::Qt, Seg::Ut];

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Segments([Span; 8]);

impl Segments {
    pub const fn new() -> Self {
        Segments([Span { start: 0, len: 0 }; 8])
    }

    pub fn get(&self, s: Seg) -> Span {
        self.0[s as usize]
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct Marker {
    origin: usize,
    last: usize,
    next: usize,
}

impl Marker {
    pub(crate) const fn new(at: usize) -> Self {
        Marker { origin: at, last: at, next: 0 }
    }

    /// Ends `seg` at absolute bit `at`; segments skipped since the previous cut get length 0.
    pub(crate) fn cut(&mut self, segs: &mut Segments, seg: Seg, at: usize) {
        let start = self.last - self.origin;
        while self.next < seg as usize {
            segs.0[self.next] = Span { start, len: 0 };
            self.next += 1;
        }
        segs.0[seg as usize] = Span { start, len: at - self.last };
        self.last = at;
        self.next = seg as usize + 1;
    }

    /// Gives the remaining segments length 0 and returns the packet length.
    pub(crate) fn finish(&mut self, segs: &mut Segments) -> usize {
        let start = self.offset();
        while self.next < SEGS.len() {
            segs.0[self.next] = Span { start, len: 0 };
            self.next += 1;
        }
        start
    }

    pub(crate) fn offset(&self) -> usize {
        self.last - self.origin
    }
}

#[derive(Clone)]
pub struct CompressTrace {
    pub t: isize,
    pub new_mask: bool,
    pub send_mask: bool,
    pub uncompressed: bool,
    pub raw: bool,
    /// V_t set by the case 2 ramp, if any.
    pub ramp_k: Option<u8>,
    pub d_t: [Block; BUF_LEN],
    pub m_t: [Block; BUF_LEN],
    pub b_t: [Block; BUF_LEN],
    pub x_t: [Block; BUF_LEN],
    pub v_t: isize,
    pub big_c_t: isize,
    pub e_t: Option<bool>,
    pub c_t: Option<bool>,
    pub dot_d_t: bool,
    pub segments: Segments,
    pub len_bits: usize,
}

impl CompressTrace {
    pub(crate) const fn new() -> Self {
        CompressTrace {
            t: -1,
            new_mask: false,
            send_mask: false,
            uncompressed: false,
            raw: false,
            ramp_k: None,
            d_t: [0; BUF_LEN],
            m_t: [0; BUF_LEN],
            b_t: [0; BUF_LEN],
            x_t: [0; BUF_LEN],
            v_t: 0,
            big_c_t: 0,
            e_t: None,
            c_t: None,
            dot_d_t: false,
            segments: Segments::new(),
            len_bits: 0,
        }
    }
}

#[derive(Clone)]
pub struct DecompressTrace {
    /// F the packet was parsed with, also when discovery rolled it back.
    pub f: Option<u16>,
    /// The packet was a raw chapter 6 case 2 packet.
    pub raw: bool,
    pub x_t: [Block; BUF_LEN],
    pub has_x_t: bool,
    pub m_staged: [Block; BUF_LEN],
    pub has_m_staged: bool,
    pub m_chg: [Block; BUF_LEN],
    pub has_m_chg: bool,
    pub m_full: [Block; BUF_LEN],
    pub has_m_full: bool,
    pub m_committed: [Block; BUF_LEN],
    pub v_t: Option<u8>,
    pub e_t: Option<bool>,
    pub c_t: Option<bool>,
    pub dot_d_t: Option<bool>,
    pub send_mask: Option<bool>,
    pub uncompressed: Option<bool>,
    pub mask_inc_whole: bool,
    pub mask_inc_changed: bool,
    pub count_f_mismatch: bool,
    pub vt_gap_ok: Option<bool>,
    pub status: u8,
    pub segments: Segments,
    /// Segment being read and its start offset when parsing failed.
    pub failed_at: Option<(Seg, usize)>,
    /// F could not be discovered from this packet.
    pub f_unknown: bool,
    /// F was taken from this packet although the packet was too short to decode.
    pub weak_discovery: bool,
    /// The F of this packet was rejected because its mask contradicts the changed bits.
    pub f_rejected: bool,
    /// Status ring, oldest first; `ring_len` entries are valid.
    pub ring: [u8; MAX_VT_HISTORY],
    pub ring_len: usize,
    pub(crate) reading: Seg,
    pub(crate) mk: Marker,
}

impl DecompressTrace {
    pub(crate) const fn new() -> Self {
        DecompressTrace {
            f: None,
            raw: false,
            x_t: [0; BUF_LEN],
            has_x_t: false,
            m_staged: [0; BUF_LEN],
            has_m_staged: false,
            m_chg: [0; BUF_LEN],
            has_m_chg: false,
            m_full: [0; BUF_LEN],
            has_m_full: false,
            m_committed: [0; BUF_LEN],
            v_t: None,
            e_t: None,
            c_t: None,
            dot_d_t: None,
            send_mask: None,
            uncompressed: None,
            mask_inc_whole: false,
            mask_inc_changed: false,
            count_f_mismatch: false,
            vt_gap_ok: None,
            status: 0,
            segments: Segments::new(),
            failed_at: None,
            f_unknown: false,
            weak_discovery: false,
            f_rejected: false,
            ring: [0; MAX_VT_HISTORY],
            ring_len: 0,
            reading: Seg::RleX,
            mk: Marker::new(0),
        }
    }

    /// Field by field, so no 40 KiB temporary lands on the stack.
    pub(crate) fn reset(&mut self) {
        self.x_t.fill(0);
        self.m_staged.fill(0);
        self.m_chg.fill(0);
        self.m_full.fill(0);
        self.m_committed.fill(0);
        self.f = None;
        self.raw = false;
        self.has_x_t = false;
        self.has_m_staged = false;
        self.has_m_chg = false;
        self.has_m_full = false;
        self.v_t = None;
        self.e_t = None;
        self.c_t = None;
        self.dot_d_t = None;
        self.send_mask = None;
        self.uncompressed = None;
        self.mask_inc_whole = false;
        self.mask_inc_changed = false;
        self.count_f_mismatch = false;
        self.vt_gap_ok = None;
        self.status = 0;
        self.segments = Segments::new();
        self.failed_at = None;
        self.f_unknown = false;
        self.weak_discovery = false;
        self.f_rejected = false;
        self.ring_len = 0;
        self.reading = Seg::RleX;
        self.mk = Marker::new(0);
    }

    pub(crate) fn begin(&mut self, at: usize) {
        self.segments = Segments::new();
        self.mk = Marker::new(at);
        self.reading = Seg::RleX;
    }

    pub(crate) fn cut(&mut self, seg: Seg, at: usize) {
        self.mk.cut(&mut self.segments, seg, at);
    }

    /// Records where parsing stopped; the segment being read and all later ones get length 0.
    pub(crate) fn fail(&mut self) {
        self.failed_at = Some((self.reading, self.mk.offset()));
        self.mk.finish(&mut self.segments);
    }
}

#[cfg(test)]
mod tests {
    use super::{Marker, Seg, Segments, Span};

    #[test]
    fn cut_records_spans_relative_to_the_origin() {
        let mut segs = Segments::new();
        let mut mk = Marker::new(100);
        mk.cut(&mut segs, Seg::RleX, 110);
        mk.cut(&mut segs, Seg::Vt, 114);
        assert_eq!(segs.get(Seg::RleX), Span { start: 0, len: 10 });
        assert_eq!(segs.get(Seg::Vt), Span { start: 10, len: 4 });
    }

    #[test]
    fn skipped_segments_get_length_zero_at_the_current_offset() {
        let mut segs = Segments::new();
        let mut mk = Marker::new(0);
        mk.cut(&mut segs, Seg::Vt, 6);
        mk.cut(&mut segs, Seg::Dt, 7);
        assert_eq!(segs.get(Seg::RleX), Span { start: 0, len: 0 });
        assert_eq!(segs.get(Seg::Vt), Span { start: 0, len: 6 });
        assert_eq!(segs.get(Seg::Et), Span { start: 6, len: 0 });
        assert_eq!(segs.get(Seg::Kt), Span { start: 6, len: 0 });
        assert_eq!(segs.get(Seg::Ct), Span { start: 6, len: 0 });
        assert_eq!(segs.get(Seg::Dt), Span { start: 6, len: 1 });
    }

    #[test]
    fn finish_fills_the_rest_and_returns_the_length() {
        let mut segs = Segments::new();
        let mut mk = Marker::new(64);
        mk.cut(&mut segs, Seg::RleX, 66);
        assert_eq!(mk.finish(&mut segs), 2);
        assert_eq!(segs.get(Seg::Ut), Span { start: 2, len: 0 });
    }
}
