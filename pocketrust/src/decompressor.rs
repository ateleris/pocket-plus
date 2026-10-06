use crate::be::{try_read_be, try_read_reverse_be_inverting};
use crate::bitstream::BitReader;
use crate::count::try_read_count;
use crate::mask::invert_mask_shift;
use crate::rle::try_read_reverse_rle;
use crate::{blocks_for, Block, BLOCK_BITS, BLOCK_MASK, BUF_LEN, MAX_PACKET_BITS};
#[cfg(feature = "trace")]
use crate::trace::{DecompressTrace, Seg};

const MAX_VT_HISTORY: usize = 16;

pub struct DecompressScratch {
    x_t: [Block; BUF_LEN],
    m_delta: [Block; BUF_LEN], // staged M_t; committed to self.m only when the packet is accepted
    m_chg: [Block; BUF_LEN],   // recovered new mask values at the changed (x_t) bits; 0 elsewhere
    xm_t: [Block; BUF_LEN],
}

impl Default for DecompressScratch {
    fn default() -> Self {
        Self::new()
    }
}

impl DecompressScratch {
    pub fn new() -> Self {
        DecompressScratch {
            x_t: [0; BUF_LEN],
            m_delta: [0; BUF_LEN],
            m_chg: [0; BUF_LEN],
            xm_t: [0; BUF_LEN],
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum DecompressStatus {
    Guaranteed,
    Unguaranteed,
}

/// The longest compressed packet a 65535-bit field can produce, as the CCSDS 124.0 yellow book bounds it.
pub const MAX_COMPRESSED_PACKET_BITS: usize = 622_627;

/// Why a received frame cannot be decoded at all.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FrameError {
    ZeroLength,
    TooLong(usize),
    Truncated { declared: usize, available: usize },
}

impl core::fmt::Display for FrameError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            FrameError::ZeroLength => write!(f, "frame declares 0 bits"),
            FrameError::TooLong(n) => write!(f, "frame declares {} bits, above {}", n, MAX_COMPRESSED_PACKET_BITS),
            FrameError::Truncated { declared, available } => {
                write!(f, "frame declares {} bits but {} arrived", declared, available)
            }
        }
    }
}

/// Checks a received frame's declared length before decoding: decoding stops at a frame that fails.
pub fn check_frame(declared_bits: usize, available_bits: usize) -> Result<(), FrameError> {
    if declared_bits == 0 {
        return Err(FrameError::ZeroLength);
    }
    if declared_bits > MAX_COMPRESSED_PACKET_BITS {
        return Err(FrameError::TooLong(declared_bits));
    }
    if declared_bits > available_bits {
        return Err(FrameError::Truncated { declared: declared_bits, available: available_bits });
    }
    Ok(())
}

struct DecodeFlags {
    vt: u8,
    rt: bool,
    next_pos: usize,
    next_idx: u8,
}

#[derive(Clone)]
pub struct DecompressorContext {
    num_blocks: usize,
    last_block_bits: u8,
    f_known: bool,
    i: [Block; BUF_LEN],
    m: [Block; BUF_LEN],
    mask_inc_changed: bool,
    mask_inc_whole: bool,
    count_f_mismatch: bool,
    ring: [u8; MAX_VT_HISTORY],
    ring_index: usize,
    ring_count: usize,
    #[cfg(feature = "trace")]
    trace: DecompressTrace,
}

impl DecompressorContext {
    pub fn init() -> Self {
        DecompressorContext {
            num_blocks: 0,
            last_block_bits: 0,
            f_known: false,
            i: [0; BUF_LEN],
            m: [0; BUF_LEN],
            mask_inc_changed: false,
            mask_inc_whole: false,
            count_f_mismatch: false,
            ring: [0; MAX_VT_HISTORY],
            ring_index: 0,
            ring_count: 0,
            #[cfg(feature = "trace")]
            trace: DecompressTrace::new(),
        }
    }

    pub fn init_f_known(f: u16) -> Self {
        let mut s = Self::init();
        s.set_f(f);
        s
    }

    pub fn discovered_f(&self) -> Option<u16> {
        if self.f_known {
            Some(self.f() as u16)
        } else {
            None
        }
    }
    
    fn f(&self) -> usize {
        if self.last_block_bits == 0 {
            self.num_blocks * BLOCK_BITS
        } else {
            (self.num_blocks - 1) * BLOCK_BITS + self.last_block_bits as usize
        }
    }

    fn set_f(&mut self, f: u16) {
        self.num_blocks = blocks_for(f as usize);
        self.last_block_bits = (f as usize & BLOCK_MASK) as u8;
        self.f_known = true;
    }

    pub fn notify_packet_undecodable(&mut self) {
        self.push_status(0x01);
    }
    
    pub fn notify_packet_loss(&mut self, lost_count: usize) {
        for _ in 0..lost_count {
            self.push_status(0x02);
        }
    }

    fn decompress_internal(
        &mut self,
        input: &[Block],
        in_pos: usize,
        in_pos_i: u8,
        num_bits: usize,
        i_out: &mut [Block],
        s: &mut DecompressScratch,
    ) -> Option<DecodeFlags> {
        self.mask_inc_changed = false;
        self.mask_inc_whole = false;
        self.count_f_mismatch = false;
        let n = self.num_blocks;
        let f = self.f();
        let mut r = BitReader::with_len(input, in_pos, in_pos_i, num_bits);
        trace!(self.trace.begin(r.bit_pos()););

        // h_t
        s.x_t[..n].fill(0);
        let (x_span, x_ones) = try_read_reverse_rle(&mut r, f, &mut s.x_t)?;
        if x_span > f {
            return None;
        }
        let x_t_zero = x_ones == 0;
        trace!(
            self.trace.cut(Seg::RleX, r.bit_pos());
            self.trace.x_t[..n].copy_from_slice(&s.x_t[..n]);
            self.trace.reading = Seg::Vt;
        );

        let v_t = r.try_read(4)? as isize;
        trace!(
            self.trace.cut(Seg::Vt, r.bit_pos());
            self.trace.v_t = Some(v_t as u8);
        );

        // e_t / k_t
        let mut c_t: i8 = -1;
        let mut y_present = false;
        if !(v_t == 0 || x_t_zero) {
            trace!(self.trace.reading = Seg::Et;);
            let e_t = r.try_bit()?;
            trace!(
                self.trace.cut(Seg::Et, r.bit_pos());
                self.trace.e_t = Some(e_t == 1);
            );
            if e_t == 1 {
                trace!(self.trace.reading = Seg::Kt;);
                s.m_chg[..n].fill(0);
                let (xt, mchg) = (&s.x_t, &mut s.m_chg);
                try_read_reverse_be_inverting(&mut r, &xt[..n], mchg)?;
                trace!(
                    self.trace.cut(Seg::Kt, r.bit_pos());
                    self.trace.reading = Seg::Ct;
                );
                y_present = true;
                c_t = r.try_bit()? as i8;
                trace!(
                    self.trace.cut(Seg::Ct, r.bit_pos());
                    self.trace.c_t = Some(c_t == 1);
                );
            }
        }

        trace!(self.trace.reading = Seg::Dt;);
        let dot_d_t = r.try_bit()? == 1;
        trace!(
            self.trace.cut(Seg::Dt, r.bit_pos());
            self.trace.dot_d_t = Some(dot_d_t);
            self.trace.reading = Seg::Qt;
        );

        // q_t
        let mut m_full: Option<[Block; BUF_LEN]> = None;
        if !dot_d_t {
            let send_mask = r.try_bit()? == 1;
            trace!(self.trace.send_mask = Some(send_mask););
            if send_mask {
                let mut shift: [Block; BUF_LEN] = [0; BUF_LEN];
                let (m_span, _) = try_read_reverse_rle(&mut r, f, &mut shift)?;
                if m_span > f {
                    return None;
                }
                m_full = Some(invert_mask_shift(&shift, f));
            }
        }
        trace!(self.trace.cut(Seg::Qt, r.bit_pos()););

        // Mask update
        if x_t_zero {
            s.m_delta[..n].copy_from_slice(&self.m[..n]);
        } else if v_t == 0 {
            for i in 0..n {
                s.m_delta[i] = self.m[i] ^ s.x_t[i];
            }
        } else if y_present {
            for i in 0..n {
                s.m_delta[i] = (self.m[i] & !s.x_t[i]) | (s.m_chg[i] & s.x_t[i]);
            }
        } else {
            for i in 0..n {
                s.m_delta[i] = self.m[i] | s.x_t[i];
            }
        }

        if let Some(m_full) = m_full.as_ref() {
            let mut inc_whole = false;
            let mut inc_changed = false;
            for i in 0..n {
                let diff = m_full[i] ^ s.m_delta[i];
                if diff != 0 {
                    inc_whole = true;
                }
                if diff & s.x_t[i] != 0 {
                    inc_changed = true;
                }
            }
            self.mask_inc_whole = inc_whole;
            self.mask_inc_changed = inc_changed;
            s.m_delta[..n].copy_from_slice(&m_full[..n]);
        }
        trace!(
            self.trace.m_staged[..n].copy_from_slice(&s.m_delta[..n]);
            if y_present {
                self.trace.m_chg[..n].copy_from_slice(&s.m_chg[..n]);
            }
            self.trace.has_m_full = m_full.is_some();
            if let Some(m) = m_full.as_ref() {
                self.trace.m_full[..n].copy_from_slice(&m[..n]);
            }
            self.trace.reading = Seg::Ut;
        );

        // xm_t (X_t OR M_t)
        if c_t == 1 {
            for i in 0..n {
                s.xm_t[i] = s.x_t[i] | s.m_delta[i];
            }
        }

        // u_t
        let mut rt = false;
        if dot_d_t {
            let mask: &[Block] = if c_t == 1 {
                &s.xm_t[..n]
            } else {
                &s.m_delta[..n]
            };
            try_read_be(&mut r, mask, &self.i[..n], i_out)?;
        } else {
            let uncompressed_bit = r.try_bit()?;
            rt = uncompressed_bit == 1;
            trace!(self.trace.uncompressed = Some(rt););
            if rt {
                let count_f = try_read_count(&mut r)?;
                if count_f as usize != f {
                    self.count_f_mismatch = true;
                }
                for i in 0..n {
                    if self.last_block_bits != 0 && i == n - 1 {
                        let bits = self.last_block_bits;
                        i_out[i] = r.try_read(bits)? << (BLOCK_BITS - bits as usize);
                    } else {
                        i_out[i] = r.try_read(BLOCK_BITS as u8)?;
                    }
                }
            } else if c_t == 1 {
                try_read_be(&mut r, &s.xm_t[..n], &self.i[..n], i_out)?;
            } else {
                try_read_be(&mut r, &s.m_delta[..n], &self.i[..n], i_out)?;
            }
        }

        trace!(
            self.trace.cut(Seg::Ut, r.bit_pos());
            self.trace.mk.finish(&mut self.trace.segments);
        );
        Some(DecodeFlags {
            vt: (v_t as u8) & 0x0F,
            rt,
            next_pos: r.pos,
            next_idx: r.idx,
        })
    }

    fn push_status(&mut self, status: u8) {
        self.ring[self.ring_index] = status;
        self.ring_index = (self.ring_index + 1) & (MAX_VT_HISTORY - 1);
        if self.ring_count < MAX_VT_HISTORY {
            self.ring_count += 1;
        }
    }

    fn vt_gap_ok(&self, vt: u8) -> bool {
        let mut idx = (self.ring_index + MAX_VT_HISTORY - 1) & (MAX_VT_HISTORY - 1);
        let mut walk = self.ring_count;
        for _gap in 0..=(vt as usize) {
            if walk == 0 {
                return false;
            }
            if self.ring[idx] == 0x00 {
                return true;
            }
            idx = (idx + MAX_VT_HISTORY - 1) & (MAX_VT_HISTORY - 1);
            walk -= 1;
        }
        false
    }

    pub fn decompress(
        &mut self,
        input: &[Block],
        in_pos: usize,
        in_pos_i: u8,
        num_bits: usize,
        i_out: &mut [Block],
        scratch: &mut DecompressScratch,
    ) -> (DecompressStatus, usize, u8) {
        trace!(self.trace.reset(););
        if !self.f_known {
            match discover_at(input, in_pos, in_pos_i, num_bits) {
                Discovery::Strict(f) => {
                    let pending = self.clone();
                    self.set_f(f);
                    let result = self.decompress(input, in_pos, in_pos_i, num_bits, i_out, scratch);
                    if result.0 != DecompressStatus::Guaranteed {
                        trace!(
                            let mut pending = pending;
                            core::mem::swap(&mut self.trace, &mut pending.trace);
                        );
                        *self = pending;
                        trace!(self.trace_commit(0x01););
                    }
                    return result;
                }
                Discovery::Weak { f, vt } => {
                    let pending = self.clone();
                    self.set_f(f);
                    let _parsed = self.decompress_internal(input, in_pos, in_pos_i, num_bits, i_out, scratch);
                    let mask_reject = self.mask_inc_changed && vt > 0;
                    trace!(
                        if _parsed.is_none() {
                            self.trace.fail();
                        }
                        self.trace.mask_inc_whole = self.mask_inc_whole;
                        self.trace.mask_inc_changed = self.mask_inc_changed;
                        let mut pending = pending;
                        core::mem::swap(&mut self.trace, &mut pending.trace);
                    );
                    *self = pending;
                    if mask_reject {
                        trace!(
                            self.trace.f_rejected = true;
                            self.trace_commit(0x01);
                        );
                        return (DecompressStatus::Unguaranteed, in_pos, in_pos_i);
                    }
                    self.set_f(f);
                    self.notify_packet_undecodable();
                    trace!(
                        self.trace.weak_discovery = true;
                        self.trace_commit(0x01);
                    );
                    return (DecompressStatus::Unguaranteed, in_pos, in_pos_i);
                }
                Discovery::None => {
                    trace!(
                        self.trace.f_unknown = true;
                        self.trace_commit(0x01);
                    );
                    return (DecompressStatus::Unguaranteed, in_pos, in_pos_i);
                }
            }
        }

        let flags = match self.decompress_internal(input, in_pos, in_pos_i, num_bits, i_out, scratch) {
            Some(f) => f,
            None => {
                self.push_status(0x01);
                trace!(
                    self.trace.fail();
                    self.trace_commit(0x01);
                );
                return (DecompressStatus::Unguaranteed, in_pos, in_pos_i);
            }
        };

        let vt_ok = self.vt_gap_ok(flags.vt);
        let mchg_fatal = if flags.rt {
            (self.mask_inc_changed && flags.vt > 0) || (self.mask_inc_whole && vt_ok)
        } else {
            self.mask_inc_whole
        };

        let guaranteed = if self.count_f_mismatch {
            false
        } else if mchg_fatal {
            false
        } else if flags.rt {
            true
        } else {
            vt_ok
        };

        let status = if guaranteed {
            self.i[..self.num_blocks].copy_from_slice(&i_out[..self.num_blocks]);
            self.m[..self.num_blocks].copy_from_slice(&scratch.m_delta[..self.num_blocks]);
            0x00
        } else {
            // Rejected
            0x01
        };

        self.push_status(status);
        trace!(
            self.trace.mask_inc_whole = self.mask_inc_whole;
            self.trace.mask_inc_changed = self.mask_inc_changed;
            self.trace.count_f_mismatch = self.count_f_mismatch;
            self.trace.vt_gap_ok = Some(vt_ok);
            self.trace_commit(status);
        );
        let verdict = if status == 0x00 {
            DecompressStatus::Guaranteed
        } else {
            DecompressStatus::Unguaranteed
        };
        (verdict, flags.next_pos, flags.next_idx)
    }
}

#[cfg(feature = "trace")]
impl DecompressorContext {
    pub fn last_trace(&self) -> &DecompressTrace {
        &self.trace
    }

    pub fn state_m(&self) -> &[Block] {
        &self.m[..self.num_blocks]
    }

    pub fn state_i(&self) -> &[Block] {
        &self.i[..self.num_blocks]
    }

    fn trace_commit(&mut self, status: u8) {
        let n = self.num_blocks;
        self.trace.status = status;
        self.trace.m_committed[..n].copy_from_slice(&self.m[..n]);
        let first = (self.ring_index + MAX_VT_HISTORY - self.ring_count) & (MAX_VT_HISTORY - 1);
        for k in 0..self.ring_count {
            self.trace.ring[k] = self.ring[(first + k) & (MAX_VT_HISTORY - 1)];
        }
        self.trace.ring_len = self.ring_count;
    }
}

enum Discovery {
    Strict(u16),
    Weak { f: u16, vt: u8 },
    None,
}

fn discover_at(data: &[Block], in_pos: usize, in_pos_i: u8, num_bits: usize) -> Discovery {
    const CAP: usize = MAX_PACKET_BITS;
    let mut r = BitReader::with_len(data, in_pos, in_pos_i, num_bits);

    let mut scratch: [Block; BUF_LEN] = [0; BUF_LEN];
    let (x_span, x_ones) = match try_read_reverse_rle(&mut r, CAP, &mut scratch) {
        Some(v) => v,
        None => return Discovery::None,
    };
    let h_xt = x_ones as usize;
    let x_t_zero = h_xt == 0;

    let v_t = match r.try_read(4) {
        Some(v) => v,
        None => return Discovery::None,
    };

    if !(v_t == 0 || x_t_zero) {
        match r.try_bit() {
            Some(1) => {
                // e_t == 1: skip k_t (H(X_t) bits) and c_t.
                if (h_xt) > r.remaining() {
                    return Discovery::None;
                }
                for _ in 0..h_xt {
                    if r.try_bit().is_none() {
                        return Discovery::None;
                    }
                }
                if r.try_bit().is_none() {
                    return Discovery::None;
                }
            }
            Some(_) => {}
            None => return Discovery::None,
        }
    }

    match r.try_bit() {
        Some(1) => return Discovery::None, // dt=1: incremental, F not discoverable
        Some(_) => {}
        None => return Discovery::None,
    }

    let mut mask_span = 0usize;
    match r.try_bit() {
        Some(1) => {
            // ft=1: skip the full-mask RLE, keeping its span for validity.
            match try_read_reverse_rle(&mut r, CAP, &mut scratch) {
                Some((span, _)) => mask_span = span,
                None => return Discovery::None,
            }
        }
        Some(_) => {}
        None => return Discovery::None,
    }

    match r.try_bit() {
        Some(1) => {} // rt=1: reference packet
        _ => return Discovery::None,
    }

    let f = match try_read_count(&mut r) {
        Some(f) if f > 0 => f as usize,
        _ => return Discovery::None,
    };

    if f > MAX_PACKET_BITS || x_span > f || mask_span > f {
        return Discovery::None;
    }

    if r.remaining() < f {
        Discovery::Weak { f: f as u16, vt: v_t as u8 }
    } else {
        Discovery::Strict(f as u16)
    }
}

#[cfg(test)]
mod frame_tests {
    use super::{check_frame, FrameError, MAX_COMPRESSED_PACKET_BITS};

    #[test]
    fn accepts_a_frame_within_bounds() {
        assert_eq!(check_frame(100, 104), Ok(()));
        assert_eq!(check_frame(MAX_COMPRESSED_PACKET_BITS, MAX_COMPRESSED_PACKET_BITS), Ok(()));
    }

    #[test]
    fn rejects_a_zero_length() {
        assert_eq!(check_frame(0, 64), Err(FrameError::ZeroLength));
    }

    #[test]
    fn rejects_a_length_no_packet_can_have() {
        assert_eq!(check_frame(MAX_COMPRESSED_PACKET_BITS + 1, usize::MAX),
                   Err(FrameError::TooLong(MAX_COMPRESSED_PACKET_BITS + 1)));
    }

    #[test]
    fn rejects_a_frame_cut_off_before_its_declared_end() {
        assert_eq!(check_frame(100, 96), Err(FrameError::Truncated { declared: 100, available: 96 }));
    }
}
