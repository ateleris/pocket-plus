use crate::be::{try_read_be, try_read_reverse_be_inverting};
use crate::bitstream::BitReader;
use crate::count::try_read_count;
use crate::mask::invert_mask_shift;
use crate::rle::try_read_reverse_rle;
use crate::{BUF_LEN, MAX_PACKET_BITS};

const MAX_VT_HISTORY: usize = 16;

pub struct DecodeScratch {
    x_t: [u64; BUF_LEN],
    m_delta: [u64; BUF_LEN], // staged M_t; committed to self.m only when the packet is accepted
    m_chg: [u64; BUF_LEN],   // recovered new mask values at the changed (x_t) bits; 0 elsewhere
    xm_t: [u64; BUF_LEN],
}

impl Default for DecodeScratch {
    fn default() -> Self {
        Self::new()
    }
}

impl DecodeScratch {
    pub fn new() -> Self {
        DecodeScratch {
            x_t: [0; BUF_LEN],
            m_delta: [0; BUF_LEN],
            m_chg: [0; BUF_LEN],
            xm_t: [0; BUF_LEN],
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum DecodeStatus {
    Guaranteed,
    Unguaranteed,
}

struct DecodeFlags {
    vt: u8,
    rt: bool,
    next_pos: usize,
    next_idx: u8,
}

#[derive(Clone)]
pub struct DecompressorState {
    num_blocks: usize,
    last_block_bits: u8,
    f_known: bool,
    i: [u64; BUF_LEN],
    m: [u64; BUF_LEN],
    mask_inc_changed: bool,
    mask_inc_whole: bool,
    count_f_mismatch: bool,
    ring: [u8; MAX_VT_HISTORY],
    ring_index: usize,
    ring_count: usize,
}

impl DecompressorState {
    pub fn init() -> Self {
        DecompressorState {
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
            self.num_blocks * 64
        } else {
            (self.num_blocks - 1) * 64 + self.last_block_bits as usize
        }
    }

    fn set_f(&mut self, f: u16) {
        let mut num_blocks = (f as usize) >> 6;
        let last_block_bits = (f & 63) as u8;
        if last_block_bits > 0 {
            num_blocks += 1;
        }
        self.num_blocks = num_blocks;
        self.last_block_bits = last_block_bits;
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

    fn decode_internal(
        &mut self,
        input: &[u64],
        in_pos: usize,
        in_pos_i: u8,
        num_bits: usize,
        i_out: &mut [u64],
        s: &mut DecodeScratch,
    ) -> Option<DecodeFlags> {
        self.mask_inc_changed = false;
        self.mask_inc_whole = false;
        self.count_f_mismatch = false;
        let n = self.num_blocks;
        let f = self.f();
        let mut r = BitReader::with_len(input, in_pos, in_pos_i, num_bits);

        // h_t
        s.x_t[..n].fill(0);
        let (x_span, x_ones) = try_read_reverse_rle(&mut r, f, &mut s.x_t)?;
        if x_span > f {
            return None;
        }
        let x_t_zero = x_ones == 0;

        let v_t = r.try_read(4)? as isize;

        // e_t / k_t
        let mut c_t: i8 = -1;
        let mut y_present = false;
        if !(v_t == 0 || x_t_zero) {
            let e_t = r.try_bit()?;
            if e_t == 1 {
                s.m_chg[..n].fill(0);
                let (xt, mchg) = (&s.x_t, &mut s.m_chg);
                try_read_reverse_be_inverting(&mut r, &xt[..n], mchg)?;
                y_present = true;
                c_t = r.try_bit()? as i8;
            }
        }

        let dot_d_t = r.try_bit()? == 1;

        // q_t
        let mut m_full: Option<[u64; BUF_LEN]> = None;
        if !dot_d_t {
            let send_mask = r.try_bit()? == 1;
            if send_mask {
                let mut shift = [0u64; BUF_LEN];
                let (m_span, _) = try_read_reverse_rle(&mut r, f, &mut shift)?;
                if m_span > f {
                    return None;
                }
                m_full = Some(invert_mask_shift(&shift, f));
            }
        }

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

        // xm_t (X_t OR M_t)
        if c_t == 1 {
            for i in 0..n {
                s.xm_t[i] = s.x_t[i] | s.m_delta[i];
            }
        }

        // u_t
        let mut rt = false;
        if dot_d_t {
            let mask: &[u64] = if c_t == 1 {
                &s.xm_t[..n]
            } else {
                &s.m_delta[..n]
            };
            try_read_be(&mut r, mask, &self.i[..n], i_out)?;
        } else {
            let uncompressed_bit = r.try_bit()?;
            rt = uncompressed_bit == 1;
            if rt {
                let count_f = try_read_count(&mut r)?;
                if count_f as usize != f {
                    self.count_f_mismatch = true;
                }
                for i in 0..n {
                    if self.last_block_bits != 0 && i == n - 1 {
                        let bits = self.last_block_bits;
                        i_out[i] = r.try_read(bits)? << (64 - bits);
                    } else {
                        i_out[i] = r.try_read(64)?;
                    }
                }
            } else if c_t == 1 {
                try_read_be(&mut r, &s.xm_t[..n], &self.i[..n], i_out)?;
            } else {
                try_read_be(&mut r, &s.m_delta[..n], &self.i[..n], i_out)?;
            }
        }

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

    pub fn decode(
        &mut self,
        input: &[u64],
        in_pos: usize,
        in_pos_i: u8,
        num_bits: usize,
        i_out: &mut [u64],
        scratch: &mut DecodeScratch,
    ) -> (DecodeStatus, usize, u8) {
        if !self.f_known {
            match discover_at(input, in_pos, in_pos_i, num_bits) {
                Discovery::Strict(f) => {
                    let pending = self.clone();
                    self.set_f(f);
                    let result = self.decode(input, in_pos, in_pos_i, num_bits, i_out, scratch);
                    if result.0 != DecodeStatus::Guaranteed {
                        *self = pending;
                    }
                    return result;
                }
                Discovery::Weak { f, vt } => {
                    let pending = self.clone();
                    self.set_f(f);
                    let _ = self.decode_internal(input, in_pos, in_pos_i, num_bits, i_out, scratch);
                    let mask_reject = self.mask_inc_changed && vt > 0;
                    *self = pending;
                    if mask_reject {
                        return (DecodeStatus::Unguaranteed, in_pos, in_pos_i);
                    }
                    self.set_f(f);
                    self.notify_packet_undecodable();
                    return (DecodeStatus::Unguaranteed, in_pos, in_pos_i);
                }
                Discovery::None => return (DecodeStatus::Unguaranteed, in_pos, in_pos_i),
            }
        }

        let flags = match self.decode_internal(input, in_pos, in_pos_i, num_bits, i_out, scratch) {
            Some(f) => f,
            None => {
                self.push_status(0x01);
                return (DecodeStatus::Unguaranteed, in_pos, in_pos_i);
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
        let verdict = if status == 0x00 {
            DecodeStatus::Guaranteed
        } else {
            DecodeStatus::Unguaranteed
        };
        (verdict, flags.next_pos, flags.next_idx)
    }
}

enum Discovery {
    Strict(u16),
    Weak { f: u16, vt: u8 },
    None,
}

fn discover_at(data: &[u64], in_pos: usize, in_pos_i: u8, num_bits: usize) -> Discovery {
    const CAP: usize = MAX_PACKET_BITS;
    let mut r = BitReader::with_len(data, in_pos, in_pos_i, num_bits);

    let mut scratch = [0u64; BUF_LEN];
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
