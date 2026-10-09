use crate::{blocks_for, Block, BLOCK_BITS, BLOCK_MASK, BUF_LEN, MAX_ROBUSTNESS};
use crate::be::{be, reverse_be_inverting};
use crate::bitstream::BitWriter;
use crate::count::count;
use crate::rle::reverse_rle;
#[cfg(feature = "trace")]
use crate::trace::{CompressTrace, Marker, Seg, Segments};

pub struct CompressScratch {
    x_t: [Block; BUF_LEN],
    y_t: [Block; BUF_LEN],
    m_t_shift: [Block; BUF_LEN],
    xm_t: [Block; BUF_LEN],
}

impl Default for CompressScratch {
    fn default() -> Self {
        Self::new()
    }
}

impl CompressScratch {
    pub fn new() -> Self {
        CompressScratch {
            x_t: [0; BUF_LEN],
            y_t: [0; BUF_LEN],
            m_t_shift: [0; BUF_LEN],
            xm_t: [0; BUF_LEN],
        }
    }
}

/// Why `compress` or `set_initial_mask` rejected its input.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CompressError {
    /// R is outside the CCSDS 124.0-B-1 range 0..=7.
    RobustnessOutOfRange(isize),
    /// For t <= R a packet must be uncompressed, and send its mask when R > 0 (CCSDS 124.0-B-1 3.3.2).
    InitPhaseFlags { t: isize, robustness: isize },
    /// Bits after the F-bit field are set.
    PaddingNotZero,
    /// F is 0: there is no field to compress.
    FieldWidthZero,
}

impl core::fmt::Display for CompressError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            CompressError::RobustnessOutOfRange(r) => write!(f, "robustness {} outside 0..=7", r),
            CompressError::InitPhaseFlags { t, robustness } => write!(
                f, "packet {} of the init phase (R = {}) lacks the uncompressed or send-mask flag", t, robustness
            ),
            CompressError::PaddingNotZero => write!(f, "bits after the F-bit field are set"),
            CompressError::FieldWidthZero => write!(f, "F is 0"),
        }
    }
}

/// What a raw packet (chapter 6 case 2) does to the compressor state.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Case2 {
    /// 6.2 case 2 (a) to (c): mask update, then the D history, C_t and V_t are zeroed and V_t ramps;
    /// C_t never counts a D row at or before the raw packet.
    Literal,
    /// Mask update only. Not conformant; for comparing against `Literal`.
    KeepD,
}

pub struct CompressorContext {
    num_blocks: usize,
    last_block_bits: u8,
    t: isize,
    i: [Block; BUF_LEN],
    m: [Block; BUF_LEN],
    b: [Block; BUF_LEN],
    d: [[Block; BUF_LEN]; 8],
    num_d_zeros: u8,
    p: [bool; 16],
    d_zero: [bool; 8],
    ramp: Option<u8>,
    c_floor: isize,
    #[cfg(feature = "trace")]
    trace: CompressTrace,
}

impl CompressorContext {
    pub fn init(big_f: u16) -> Self {
        let num_blocks = blocks_for(big_f as usize);
        let last_block_bits = (big_f as usize & BLOCK_MASK) as u8;

        CompressorContext {
            num_blocks,
            last_block_bits,
            t: -1,
            m: [0; BUF_LEN],
            i: [0; BUF_LEN],
            b: [0; BUF_LEN],
            d: [[0; BUF_LEN]; 8],
            num_d_zeros: 0,
            p: [false; 16],
            d_zero: [true; 8],
            ramp: None,
            c_floor: -1,
            #[cfg(feature = "trace")]
            trace: CompressTrace::new(),
        }
    }

    pub fn t(&self) -> isize {
        self.t
    }

    pub fn f(&self) -> u16 {
        if self.last_block_bits == 0 {
            (self.num_blocks * BLOCK_BITS) as u16
        } else {
            ((self.num_blocks - 1) * BLOCK_BITS + self.last_block_bits as usize) as u16
        }
    }

    fn padding_ok(&self, v: &[Block]) -> bool {
        self.last_block_bits == 0 || v[self.num_blocks - 1] & (Block::MAX >> self.last_block_bits) == 0
    }

    /// Advances t and runs the mask update of 4.2; returns the ring slot of D_t.
    fn advance(&mut self, i_t: &[Block], new_mask: bool) -> usize {
        self.t += 1;
        let p_i = (self.t as usize) & (self.p.len() - 1);
        self.p[p_i] = new_mask;

        let nb = self.num_blocks;
        let d_t_i = (self.t as usize) & (self.d.len() - 1);

        if self.t >= self.d.len() as isize {
            if self.t - self.d.len() as isize <= self.c_floor {
                self.num_d_zeros = 0;
            } else if self.d_zero[d_t_i] {
                self.num_d_zeros = self.num_d_zeros.saturating_add(1);
            } else {
                self.num_d_zeros = 0;
            }
        }

        if self.t == 0 {
            self.b[..nb].fill(0);
            self.i[..nb].copy_from_slice(&i_t[..nb]);
        } else if new_mask {
            let drow = &mut self.d[d_t_i];
            for i in 0..nb {
                let mt = (i_t[i] ^ self.i[i]) | self.b[i];
                drow[i] = mt ^ self.m[i];
                self.m[i] = mt;
                self.b[i] = 0;
                self.i[i] = i_t[i];
            }
            self.d_zero[d_t_i] = self.d[d_t_i][..nb].iter().fold(0, |a, &b| a | b) == 0;
        } else {
            let mut dor: Block = 0;
            {
                let drow = &mut self.d[d_t_i];
                for i in 0..nb {
                    let dd = (i_t[i] ^ self.i[i]) & !self.m[i];
                    drow[i] = dd;
                    dor |= dd;
                }
            }
            self.d_zero[d_t_i] = dor == 0;
            for i in 0..nb {
                let x = i_t[i] ^ self.i[i];
                self.m[i] |= x;
                self.b[i] |= x;
            }
            self.i[..nb].copy_from_slice(&i_t[..nb]);
        }
        d_t_i
    }

    pub fn set_initial_mask(&mut self, mask: &[Block]) -> Result<(), CompressError> {
        if self.num_blocks == 0 {
            return Err(CompressError::FieldWidthZero);
        }
        if !self.padding_ok(mask) {
            return Err(CompressError::PaddingNotZero);
        }
        for i in 0..self.num_blocks {
            self.m[i] = mask[i];
            self.b[i] = mask[i];
        }
        Ok(())
    }

    pub fn compress(
        &mut self,
        i_t: &[Block],
        robustness: isize,
        new_mask: bool,
        send_mask: bool,
        uncompressed: bool,
        out: &mut [Block],
        out_pos: usize,
        out_pos_i: u8,
        scratch: &mut CompressScratch,
    ) -> Result<(usize, u8), CompressError> {
        if self.num_blocks == 0 {
            return Err(CompressError::FieldWidthZero);
        }
        if !(0..=MAX_ROBUSTNESS).contains(&robustness) {
            return Err(CompressError::RobustnessOutOfRange(robustness));
        }
        let t = self.t + 1;
        if t <= robustness && (!uncompressed || (robustness > 0 && !send_mask)) {
            return Err(CompressError::InitPhaseFlags { t, robustness });
        }
        if !self.padding_ok(i_t) {
            return Err(CompressError::PaddingNotZero);
        }
        let d_t_i = self.advance(i_t, new_mask);
        let nb = self.num_blocks;

        trace!(
            self.trace.raw = false;
            self.trace.ramp_k = None;
            self.trace.t = self.t;
            self.trace.new_mask = new_mask;
            self.trace.send_mask = send_mask;
            self.trace.uncompressed = uncompressed;
            if self.t == 0 {
                self.trace.d_t[..nb].fill(0);
            } else {
                self.trace.d_t[..nb].copy_from_slice(&self.d[d_t_i][..nb]);
            }
            self.trace.m_t[..nb].copy_from_slice(&self.m[..nb]);
            self.trace.b_t[..nb].copy_from_slice(&self.b[..nb]);
        );

        // 5.3.2 accuracy window
        let dot_d_t = !send_mask && !uncompressed;
        let t = self.t as usize;
        let cap = t.min(15).saturating_sub(robustness as usize);
        let mut big_c_t = 0usize;
        let mut scrolled_out = false;
        while big_c_t < cap {
            let t_prime = t - robustness as usize - 1 - big_c_t;
            if t_prime as isize <= self.c_floor {
                break;
            }
            if t_prime + self.d.len() <= t {
                scrolled_out = true;
                break;
            }
            if !self.d_zero[t_prime & (self.d.len() - 1)] {
                break;
            }
            big_c_t += 1;
            if t_prime == 0 {
                break;
            }
        }
        if scrolled_out {
            big_c_t += (self.num_d_zeros as usize).min(cap - big_c_t);
        }
        let big_c_t = big_c_t as isize;
        let mut v_t = robustness + big_c_t;
        if self.t - robustness <= 0 {
            v_t = robustness;
        }
        let mut _ramp_k = None;
        if let Some(k) = self.ramp {
            if k as isize <= robustness {
                v_t = k as isize;
                self.ramp = Some(k + 1);
                _ramp_k = Some(k);
            } else {
                self.ramp = None;
            }
        }
        trace!(
            self.trace.v_t = v_t;
            self.trace.big_c_t = big_c_t;
            self.trace.ramp_k = _ramp_k;
        );

        // 5.3.3.1 x_t
        let x_t_zero;
        if robustness == 0 {
            if self.d_zero[d_t_i] {
                scratch.x_t[..nb].fill(0);
                x_t_zero = true;
            } else {
                scratch.x_t[..nb].copy_from_slice(&self.d[d_t_i][..nb]);
                x_t_zero = false;
            }
        } else {
            scratch.x_t[..nb].fill(0);
            let mut any = false;
            if self.t - robustness <= 0 {
                for row in 0..=d_t_i {
                    if self.d_zero[row] {
                        continue;
                    }
                    any = true;
                    for i in 0..nb {
                        scratch.x_t[i] |= self.d[row][i];
                    }
                }
            } else {
                for tt in 0..=(robustness as usize) {
                    let row = ((d_t_i as isize - tt as isize) & (self.d.len() as isize - 1)) as usize;
                    if self.d_zero[row] {
                        continue;
                    }
                    any = true;
                    for i in 0..nb {
                        scratch.x_t[i] |= self.d[row][i];
                    }
                }
            }
            x_t_zero = !any;
        }

        trace!(self.trace.x_t[..nb].copy_from_slice(&scratch.x_t[..nb]););
        let skip = ((BLOCK_BITS - self.last_block_bits as usize) & BLOCK_MASK) as u16;
        let mut w = BitWriter::new(out, out_pos, out_pos_i);
        trace!(
            self.trace.segments = Segments::new();
            let mut mk = Marker::new(w.bit_pos());
        );
        reverse_rle(&scratch.x_t[..nb], skip, &mut w);
        trace!(mk.cut(&mut self.trace.segments, Seg::RleX, w.bit_pos()););
        w.add_bits(v_t as Block, 4);
        trace!(mk.cut(&mut self.trace.segments, Seg::Vt, w.bit_pos()););

        // y_t
        scratch.y_t[..nb].fill(0);
        let mut y_t_pos = 0;
        let mut y_t_pos_i = 0;
        let y_t_zero;
        if !x_t_zero {
            let mut yw = BitWriter::new(&mut scratch.y_t, 0, 0);
            reverse_be_inverting(&self.m[..nb], &scratch.x_t[..nb], &mut yw);
            (y_t_pos, y_t_pos_i) = (yw.pos, yw.idx);
            let y_words = y_t_pos + (y_t_pos_i > 0) as usize;
            y_t_zero = scratch.y_t[..y_words].iter().fold(0, |a, &b| a | b) == 0;
        } else {
            y_t_zero = true;
        }

        // e_t
        if !(v_t == 0 || x_t_zero) {
            let bit = if y_t_zero { 0 } else { 1 };
            w.add_bits(bit, 1);
        }
        trace!(
            self.trace.e_t = if v_t == 0 || x_t_zero { None } else { Some(!y_t_zero) };
            mk.cut(&mut self.trace.segments, Seg::Et, w.bit_pos());
        );

        // k_t
        let mut c_t: i8 = -1;
        if !(v_t == 0 || x_t_zero || y_t_zero) {
            for y_t_i in 0..y_t_pos {
                w.add_bits(scratch.y_t[y_t_i], BLOCK_BITS as u8);
            }
            if y_t_pos_i > 0 {
                w.add_bits(scratch.y_t[y_t_pos] >> (BLOCK_BITS - y_t_pos_i as usize), y_t_pos_i);
            }
            trace!(mk.cut(&mut self.trace.segments, Seg::Kt, w.bit_pos()););
            let mut p_set = 0;
            for tt in 0.max(self.t - v_t)..(self.t + 1) {
                if self.p[(tt as usize) & (self.p.len() - 1)] {
                    p_set += 1;
                }
            }
            c_t = if p_set <= 1 { 0 } else { 1 };
            w.add_bits(c_t as Block, 1);
            trace!(mk.cut(&mut self.trace.segments, Seg::Ct, w.bit_pos()););
        }
        trace!(self.trace.c_t = if c_t < 0 { None } else { Some(c_t == 1) };);

        // d_t
        w.add_bits(dot_d_t as Block, 1);
        trace!(
            self.trace.dot_d_t = dot_d_t;
            mk.cut(&mut self.trace.segments, Seg::Dt, w.bit_pos());
        );

        // 5.3.3.2 q_t
        if !dot_d_t {
            if send_mask {
                w.add_bits(1, 1);
                for i in 0..nb {
                    let carry = if i + 1 < nb { self.m[i + 1] >> (BLOCK_BITS - 1) } else { 0 };
                    scratch.m_t_shift[i] = self.m[i] ^ ((self.m[i] << 1) | carry);
                }
                reverse_rle(&scratch.m_t_shift[..nb], skip, &mut w);
            } else {
                w.add_bits(0, 1);
            }
        }
        trace!(mk.cut(&mut self.trace.segments, Seg::Qt, w.bit_pos()););

        // 5.3.3.3 u_t
        if dot_d_t && c_t == 1 {
            for i in 0..nb {
                scratch.xm_t[i] = scratch.x_t[i] | self.m[i];
            }
            be(&i_t[..nb], &scratch.xm_t[..nb], &mut w);
        } else if dot_d_t && c_t != 1 {
            be(&i_t[..nb], &self.m[..nb], &mut w);
        } else if uncompressed {
            w.add_bits(1, 1);
            count(self.f(), &mut w);
            for i_t_i in 0..nb {
                if self.last_block_bits != 0 && i_t_i == nb - 1 {
                    let bits = self.last_block_bits;
                    w.add_bits(i_t[i_t_i] >> (BLOCK_BITS - bits as usize), bits);
                } else {
                    w.add_bits(i_t[i_t_i], BLOCK_BITS as u8);
                }
            }
        } else if !uncompressed && send_mask && c_t == 1 {
            w.add_bits(0, 1);
            for i in 0..nb {
                scratch.xm_t[i] = scratch.x_t[i] | self.m[i];
            }
            be(&i_t[..nb], &scratch.xm_t[..nb], &mut w);
        } else {
            w.add_bits(0, 1);
            be(&i_t[..nb], &self.m[..nb], &mut w);
        }

        trace!(
            mk.cut(&mut self.trace.segments, Seg::Ut, w.bit_pos());
            self.trace.len_bits = mk.finish(&mut self.trace.segments);
        );
        Ok((w.pos, w.idx))
    }

    pub fn skip(&mut self, i_t: &[Block], mode: Case2) -> Result<(), CompressError> {
        if self.num_blocks == 0 {
            return Err(CompressError::FieldWidthZero);
        }
        if !self.padding_ok(i_t) {
            return Err(CompressError::PaddingNotZero);
        }
        let _d_t_i = self.advance(i_t, false);
        trace!(
            let nb = self.num_blocks;
            self.trace.t = self.t;
            self.trace.raw = true;
            self.trace.ramp_k = None;
            self.trace.new_mask = false;
            self.trace.send_mask = false;
            self.trace.uncompressed = false;
            if self.t == 0 {
                self.trace.d_t[..nb].fill(0);
            } else {
                self.trace.d_t[..nb].copy_from_slice(&self.d[_d_t_i][..nb]);
            }
            self.trace.m_t[..nb].copy_from_slice(&self.m[..nb]);
            self.trace.b_t[..nb].copy_from_slice(&self.b[..nb]);
            self.trace.x_t[..nb].fill(0);
            self.trace.v_t = 0;
            self.trace.big_c_t = 0;
            self.trace.e_t = None;
            self.trace.c_t = None;
            self.trace.dot_d_t = false;
            self.trace.segments = Segments::new();
            self.trace.len_bits = 0;
        );
        if mode == Case2::Literal {
            for row in self.d.iter_mut() {
                row.fill(0);
            }
            self.d_zero = [true; 8];
            self.ramp = Some(0);
            self.num_d_zeros = 0;
            self.c_floor = self.t;
        }
        Ok(())
    }
}

#[cfg(feature = "trace")]
impl CompressorContext {
    pub fn last_trace(&self) -> &CompressTrace {
        &self.trace
    }

    pub fn state_i(&self) -> &[Block] {
        &self.i[..self.num_blocks]
    }

    pub fn state_m(&self) -> &[Block] {
        &self.m[..self.num_blocks]
    }

    pub fn state_b(&self) -> &[Block] {
        &self.b[..self.num_blocks]
    }

    /// D of the step `back` steps before the current t; `None` before t = 1 or past the ring.
    pub fn state_d(&self, back: usize) -> Option<&[Block]> {
        let t = self.t - back as isize;
        if back >= self.d.len() || t < 1 {
            return None;
        }
        Some(&self.d[(t as usize) & (self.d.len() - 1)][..self.num_blocks])
    }
}

#[cfg(test)]
mod tests {
    use super::{Case2, CompressError, CompressScratch, CompressorContext};
    use crate::{Block, BUF_LEN, BLOCK_BITS};

    fn encode(enc: &mut CompressorContext, field: &[Block], r: isize, f: bool, rt: bool) -> Result<(usize, u8), CompressError> {
        let mut scratch = CompressScratch::new();
        let mut out: [Block; 2 * BUF_LEN] = [0; 2 * BUF_LEN];
        enc.compress(field, r, false, f, rt, &mut out, 0, 0, &mut scratch)
    }

    const ZERO: [Block; BUF_LEN] = [0; BUF_LEN];

    #[test]
    fn f_returns_the_field_width_given_to_init() {
        let b = BLOCK_BITS as u16;
        for f in [0, 1, b - 1, b, b + 1, 3 * b + 5, u16::MAX] {
            assert_eq!(CompressorContext::init(f).f(), f);
        }
    }

    #[test]
    fn accepts_robustness_0_to_7() {
        for r in 0..=7 {
            assert!(encode(&mut CompressorContext::init(64), &ZERO, r, true, true).is_ok());
        }
    }

    #[test]
    fn rejects_robustness_outside_0_to_7() {
        assert_eq!(encode(&mut CompressorContext::init(64), &ZERO, 8, true, true),
                   Err(CompressError::RobustnessOutOfRange(8)));
        assert_eq!(encode(&mut CompressorContext::init(64), &ZERO, -1, true, true),
                   Err(CompressError::RobustnessOutOfRange(-1)));
    }

    #[test]
    fn rejects_an_init_packet_without_r() {
        assert_eq!(encode(&mut CompressorContext::init(64), &ZERO, 0, true, false),
                   Err(CompressError::InitPhaseFlags { t: 0, robustness: 0 }));
    }

    #[test]
    fn rejects_an_init_packet_without_f_when_r_is_positive() {
        assert_eq!(encode(&mut CompressorContext::init(64), &ZERO, 2, false, true),
                   Err(CompressError::InitPhaseFlags { t: 0, robustness: 2 }));
    }

    #[test]
    fn accepts_an_init_packet_without_f_when_r_is_zero() {
        assert!(encode(&mut CompressorContext::init(64), &ZERO, 0, false, true).is_ok());
    }

    #[test]
    fn accepts_any_flags_after_the_init_phase() {
        let mut enc = CompressorContext::init(64);
        assert!(encode(&mut enc, &ZERO, 1, true, true).is_ok());
        assert!(encode(&mut enc, &ZERO, 1, true, true).is_ok());
        assert!(encode(&mut enc, &ZERO, 1, false, false).is_ok());
    }

    #[test]
    fn rejects_set_bits_past_f() {
        let mut field = ZERO;
        field[0] = 1; // the lowest of the 4 padding bits
        assert_eq!(encode(&mut CompressorContext::init(BLOCK_BITS as u16 - 4), &field, 0, true, true),
                   Err(CompressError::PaddingNotZero));
    }

    #[test]
    fn accepts_a_full_last_word() {
        let mut field = ZERO;
        field[0] = Block::MAX;
        assert!(encode(&mut CompressorContext::init(BLOCK_BITS as u16), &field, 0, true, true).is_ok());
    }

    #[test]
    fn rejects_a_zero_field_width() {
        assert_eq!(encode(&mut CompressorContext::init(0), &ZERO, 0, true, true),
                   Err(CompressError::FieldWidthZero));
        assert_eq!(CompressorContext::init(0).set_initial_mask(&ZERO), Err(CompressError::FieldWidthZero));
    }

    #[test]
    fn rejects_a_mask_with_set_bits_past_f() {
        let mut mask = ZERO;
        mask[0] = 1;
        assert_eq!(CompressorContext::init(BLOCK_BITS as u16 - 4).set_initial_mask(&mask), Err(CompressError::PaddingNotZero));
    }

    fn field8(v: u8) -> [Block; BUF_LEN] {
        let mut f = ZERO;
        f[0] = (v as Block) << (BLOCK_BITS - 8);
        f
    }

    #[test]
    fn keep_d_skip_advances_like_a_compressed_step() {
        let mut a = CompressorContext::init(8);
        let mut b = CompressorContext::init(8);
        for ctx in [&mut a, &mut b] {
            encode(ctx, &field8(0x00), 0, true, true).unwrap();
        }
        encode(&mut a, &field8(0x30), 0, false, false).unwrap();
        b.skip(&field8(0x30), Case2::KeepD).unwrap();
        assert_eq!(a.t, b.t);
        assert_eq!(a.i, b.i);
        assert_eq!(a.m, b.m);
        assert_eq!(a.b, b.b);
        assert_eq!(a.d, b.d);
        assert_eq!(a.d_zero, b.d_zero);
        assert_eq!(a.num_d_zeros, b.num_d_zeros);
        assert_eq!(a.p, b.p);
    }

    #[test]
    fn skip_checks_its_input() {
        let mut field = ZERO;
        field[0] = 1;
        assert_eq!(
            CompressorContext::init(BLOCK_BITS as u16 - 4).skip(&field, Case2::KeepD),
            Err(CompressError::PaddingNotZero)
        );
        assert_eq!(CompressorContext::init(0).skip(&ZERO, Case2::KeepD), Err(CompressError::FieldWidthZero));
    }

    #[test]
    fn t_counts_codec_steps_without_trace() {
        let mut enc = CompressorContext::init(8);
        assert_eq!(enc.t(), -1);
        encode(&mut enc, &field8(0x00), 0, true, true).unwrap();
        enc.skip(&field8(0x01), Case2::KeepD).unwrap();
        assert_eq!(enc.t(), 1);
    }

    #[test]
    fn literal_skip_zeroes_the_d_history_and_starts_the_ramp() {
        let mut enc = CompressorContext::init(8);
        encode(&mut enc, &field8(0x00), 1, true, true).unwrap();
        encode(&mut enc, &field8(0x00), 1, true, true).unwrap();
        encode(&mut enc, &field8(0x80), 1, false, false).unwrap();
        enc.skip(&field8(0xC0), Case2::Literal).unwrap();
        assert!(enc.d.iter().all(|row| row.iter().all(|&w| w == 0)));
        assert_eq!(enc.d_zero, [true; 8]);
        assert_eq!(enc.num_d_zeros, 0);
        assert_eq!(enc.c_floor, 3);
        assert_eq!(enc.ramp, Some(0));
        assert_eq!(enc.m[0], field8(0xC0)[0]);
    }

    #[test]
    fn a_second_raw_packet_restarts_the_ramp() {
        let mut enc = CompressorContext::init(8);
        for _ in 0..3 {
            encode(&mut enc, &field8(0x00), 2, true, true).unwrap();
        }
        enc.skip(&field8(0x01), Case2::Literal).unwrap();
        encode(&mut enc, &field8(0x01), 2, false, false).unwrap();
        assert_eq!(enc.ramp, Some(1));
        enc.skip(&field8(0x01), Case2::Literal).unwrap();
        assert_eq!(enc.ramp, Some(0));
    }

    #[cfg(feature = "trace")]
    fn after_raw(mode: Case2) -> [(isize, Block, Option<u8>); 3] {
        let mut enc = CompressorContext::init(8);
        for _ in 0..3 {
            encode(&mut enc, &field8(0x00), 2, true, true).unwrap();
        }
        encode(&mut enc, &field8(0x80), 2, false, false).unwrap();
        enc.skip(&field8(0xC0), mode).unwrap();
        let mut out = [(0, 0, None); 3];
        for slot in out.iter_mut() {
            encode(&mut enc, &field8(0xC0), 2, false, false).unwrap();
            let tr = enc.last_trace();
            *slot = (tr.v_t, tr.x_t[0], tr.ramp_k);
        }
        out
    }

    #[cfg(feature = "trace")]
    #[test]
    fn literal_ramps_v_after_a_raw_packet() {
        assert_eq!(after_raw(Case2::Literal), [(0, 0, Some(0)), (1, 0, Some(1)), (2, 0, Some(2))]);
    }

    #[cfg(feature = "trace")]
    #[test]
    fn keep_d_carries_the_raw_change_in_x() {
        assert_eq!(
            after_raw(Case2::KeepD),
            [(5, field8(0xC0)[0], None), (2, field8(0x40)[0], None), (2, 0, None)]
        );
    }

    #[cfg(feature = "trace")]
    #[test]
    fn a_raw_packet_in_the_init_phase_ramps_v() {
        let mut enc = CompressorContext::init(8);
        encode(&mut enc, &field8(0x00), 2, true, true).unwrap();
        enc.skip(&field8(0x01), Case2::Literal).unwrap();
        encode(&mut enc, &field8(0x01), 2, true, true).unwrap();
        assert_eq!(enc.last_trace().v_t, 0);
        assert_eq!(enc.last_trace().ramp_k, Some(0));
    }

    #[cfg(feature = "trace")]
    #[test]
    fn state_d_reads_the_stored_d_rows() {
        let mut enc = CompressorContext::init(8);
        encode(&mut enc, &field8(0x00), 0, true, true).unwrap();
        assert!(enc.state_d(0).is_none());
        encode(&mut enc, &field8(0x80), 0, false, false).unwrap();
        encode(&mut enc, &field8(0xC0), 0, false, false).unwrap();
        assert_eq!(enc.state_d(0).unwrap()[0], field8(0x40)[0]);
        assert_eq!(enc.state_d(1).unwrap()[0], field8(0x80)[0]);
        assert!(enc.state_d(2).is_none());
        assert!(enc.state_d(8).is_none());
    }

    #[cfg(feature = "trace")]
    fn v_after_raw(mode: Case2, steps: usize) -> [isize; 20] {
        let mut enc = CompressorContext::init(8);
        encode(&mut enc, &field8(0x00), 1, true, true).unwrap();
        encode(&mut enc, &field8(0x00), 1, true, true).unwrap();
        encode(&mut enc, &field8(0x00), 1, false, false).unwrap();
        enc.skip(&field8(0x01), mode).unwrap();
        let mut v = [0; 20];
        for slot in v.iter_mut().take(steps) {
            encode(&mut enc, &field8(0x01), 1, false, false).unwrap();
            *slot = enc.last_trace().v_t;
        }
        v
    }

    #[cfg(feature = "trace")]
    #[test]
    fn literal_counts_only_rows_after_the_raw_packet() {
        let v = v_after_raw(Case2::Literal, 17);
        assert_eq!(v[..4], [0, 1, 2, 3]);
        assert_eq!(v[9], 9, "t = 13: six rows in the ring plus two that left it, all after t0");
        assert_eq!(v[15], 15);
        assert_eq!(v[16], 15);
    }
}
