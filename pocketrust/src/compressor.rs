use crate::{blocks_for, Block, BLOCK_BITS, BLOCK_MASK, BUF_LEN, MAX_ROBUSTNESS};
use crate::be::{be, reverse_be_inverting};
use crate::bitstream::BitWriter;
use crate::count::count;
use crate::rle::reverse_rle;

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
        }
    }

    fn padding_ok(&self, v: &[Block]) -> bool {
        self.last_block_bits == 0 || v[self.num_blocks - 1] & (Block::MAX >> self.last_block_bits) == 0
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
        self.t += 1;
        let p_i = (self.t as usize) & (self.p.len() - 1);
        self.p[p_i] = new_mask;

        let nb = self.num_blocks;
        let d_t_i = (self.t as usize) & (self.d.len() - 1);

        if self.t >= self.d.len() as isize {
            if self.d_zero[d_t_i] {
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

        // 5.3.2 accuracy window
        let dot_d_t = !send_mask && !uncompressed;
        let t = self.t as usize;
        let cap = t.min(15).saturating_sub(robustness as usize);
        let mut big_c_t = 0usize;
        let mut scrolled_out = false;
        while big_c_t < cap {
            let t_prime = t - robustness as usize - 1 - big_c_t;
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

        let skip = ((BLOCK_BITS - self.last_block_bits as usize) & BLOCK_MASK) as u16;
        let mut w = BitWriter::new(out, out_pos, out_pos_i);
        reverse_rle(&scratch.x_t[..nb], skip, &mut w);
        w.add_bits(v_t as Block, 4);

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

        // k_t
        let mut c_t: i8 = -1;
        if !(v_t == 0 || x_t_zero || y_t_zero) {
            for y_t_i in 0..y_t_pos {
                w.add_bits(scratch.y_t[y_t_i], BLOCK_BITS as u8);
            }
            if y_t_pos_i > 0 {
                w.add_bits(scratch.y_t[y_t_pos] >> (BLOCK_BITS - y_t_pos_i as usize), y_t_pos_i);
            }
            let mut p_set = 0;
            for tt in 0.max(self.t - v_t)..(self.t + 1) {
                if self.p[(tt as usize) & (self.p.len() - 1)] {
                    p_set += 1;
                }
            }
            c_t = if p_set <= 1 { 0 } else { 1 };
            w.add_bits(c_t as Block, 1);
        }

        // d_t
        w.add_bits(dot_d_t as Block, 1);

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
            let f = if self.last_block_bits == 0 {
                nb * BLOCK_BITS
            } else {
                (nb - 1) * BLOCK_BITS + self.last_block_bits as usize
            };
            count(f as u16, &mut w);
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

        Ok((w.pos, w.idx))
    }
}

#[cfg(test)]
mod tests {
    use super::{CompressError, CompressScratch, CompressorContext};
    use crate::{Block, BUF_LEN, BLOCK_BITS};

    fn encode(enc: &mut CompressorContext, field: &[Block], r: isize, f: bool, rt: bool) -> Result<(usize, u8), CompressError> {
        let mut scratch = CompressScratch::new();
        let mut out: [Block; 2 * BUF_LEN] = [0; 2 * BUF_LEN];
        enc.compress(field, r, false, f, rt, &mut out, 0, 0, &mut scratch)
    }

    const ZERO: [Block; BUF_LEN] = [0; BUF_LEN];

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
}
