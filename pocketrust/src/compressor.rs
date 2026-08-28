use crate::BUF_LEN;
use crate::be::{be, reverse_be_inverting};
use crate::bitstream::BitWriter;
use crate::count::count;
use crate::rle::reverse_rle;

pub struct EncodeScratch {
    x_t: [u64; BUF_LEN],
    y_t: [u64; BUF_LEN],
    m_t_shift: [u64; BUF_LEN],
    xm_t: [u64; BUF_LEN],
}

impl Default for EncodeScratch {
    fn default() -> Self {
        Self::new()
    }
}

impl EncodeScratch {
    pub fn new() -> Self {
        EncodeScratch {
            x_t: [0; BUF_LEN],
            y_t: [0; BUF_LEN],
            m_t_shift: [0; BUF_LEN],
            xm_t: [0; BUF_LEN],
        }
    }
}

pub struct CompressorState {
    num_blocks: usize,
    last_block_bits: u8,
    t: isize,
    i: [u64; BUF_LEN],
    m: [u64; BUF_LEN],
    b: [u64; BUF_LEN],
    d: [[u64; BUF_LEN]; 8],
    num_d_zeros: u8,
    p: [bool; 16],
    d_zero: [bool; 8],
}

impl CompressorState {
    pub fn init(big_f: u16) -> Self {
        let mut num_blocks = (big_f as usize) >> 6;
        let last_block_bits = (big_f & 63) as u8;
        if last_block_bits > 0 {
            num_blocks = num_blocks + 1;
        }

        CompressorState {
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

    pub fn set_initial_mask(&mut self, mask: &[u64]) {
        for i in 0..self.num_blocks {
            self.m[i] = mask[i];
            self.b[i] = mask[i];
        }
    }

    pub fn encode(
        &mut self,
        i_t: &[u64],
        robustness: isize,
        new_mask: bool,
        send_mask: bool,
        uncompressed: bool,
        out: &mut [u64],
        out_pos: usize,
        out_pos_i: u8,
        scratch: &mut EncodeScratch,
    ) -> (usize, u8) {
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
            self.d_zero[d_t_i] = self.d[d_t_i][..nb].iter().fold(0u64, |a, &b| a | b) == 0;
        } else {
            let mut dor = 0u64;
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

        let skip = (64 - self.last_block_bits as u16) & 63;
        let mut w = BitWriter::new(out, out_pos, out_pos_i);
        reverse_rle(&scratch.x_t[..nb], skip, &mut w);
        w.add_bits(v_t as u64, 4);

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
            y_t_zero = scratch.y_t[..y_words].iter().fold(0u64, |a, &b| a | b) == 0;
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
                w.add_bits(scratch.y_t[y_t_i], 64);
            }
            if y_t_pos_i > 0 {
                w.add_bits(scratch.y_t[y_t_pos] >> (64 - y_t_pos_i), y_t_pos_i);
            }
            let mut p_set = 0;
            for tt in 0.max(self.t - v_t)..(self.t + 1) {
                if self.p[(tt as usize) & (self.p.len() - 1)] {
                    p_set += 1;
                }
            }
            c_t = if p_set <= 1 { 0 } else { 1 };
            w.add_bits(c_t as u64, 1);
        }

        // d_t
        w.add_bits(dot_d_t as u64, 1);

        // 5.3.3.2 q_t
        if !dot_d_t {
            if send_mask {
                w.add_bits(1, 1);
                for i in 0..nb {
                    let carry = if i + 1 < nb { self.m[i + 1] >> 63 } else { 0 };
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
                nb as u16 * 64
            } else {
                (nb as u16 - 1) * 64 + self.last_block_bits as u16
            };
            count(f, &mut w);
            for i_t_i in 0..nb {
                if self.last_block_bits != 0 && i_t_i == nb - 1 {
                    let bits = self.last_block_bits;
                    w.add_bits(i_t[i_t_i] >> (64 - bits), bits);
                } else {
                    w.add_bits(i_t[i_t_i], 64);
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

        (w.pos, w.idx)
    }
}
