use crate::{blocks_for, Block, BLOCK_BITS, BUF_LEN};

/// Invert `m_t ^ (m_t << 1)`
pub fn invert_mask_shift(shift: &[Block], f: usize) -> [Block; BUF_LEN] {
    let mut w: [Block; BUF_LEN] = [0; BUF_LEN];
    if f == 0 {
        return w;
    }
    let nwords = blocks_for(f);
    let mut carry = false;
    for wi in (0..nwords).rev() {
        let x = shift[wi];
        let mut y = x;
        let mut s = 1;
        while s < BLOCK_BITS {
            y ^= y << s;
            s <<= 1;
        }
        if carry {
            y = !y; // lower words are full, so flipping every bit is correct
        }
        w[wi] = y;
        carry ^= x.count_ones() & 1 == 1;
    }
    w
}

#[cfg(test)]
mod tests {
    use super::invert_mask_shift;
    use crate::{blocks_for, Block, BLOCK_BITS, BLOCK_MASK, BLOCK_MSB, BLOCK_SHIFT, BUF_LEN, MAX_PACKET_BITS};

    /// Bit-by-bit reference.
    fn invert_naive(shift: &[Block], f: usize) -> [Block; BUF_LEN] {
        let mut w: [Block; BUF_LEN] = [0; BUF_LEN];
        let mut prev = 0;
        for j in (0..f).rev() {
            let sbit = (shift[j >> BLOCK_SHIFT] >> (BLOCK_BITS - 1 - (j & BLOCK_MASK))) & 1;
            let wbit = sbit ^ prev;
            if wbit == 1 {
                w[j >> BLOCK_SHIFT] |= BLOCK_MSB >> (j & BLOCK_MASK);
            }
            prev = wbit;
        }
        w
    }

    #[test]
    fn invert_mask_shift_matches_naive_fuzz() {
        let mut seed = 0x243f_6a88_85a3_08d3u64;
        let mut rng = || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            seed
        };
        for _ in 0..4000 {
            let f = 1 + (rng() % MAX_PACKET_BITS as u64) as usize;
            let nwords = blocks_for(f);
            let mut shift: [Block; BUF_LEN] = [0; BUF_LEN];
            for w in shift.iter_mut().take(nwords) {
                *w = (rng() & rng()) as Block;
            }
            let rem = f & BLOCK_MASK;
            if rem != 0 {
                shift[nwords - 1] &= Block::MAX << (BLOCK_BITS - rem);
            }
            let got = invert_mask_shift(&shift, f);
            let want = invert_naive(&shift, f);
            assert_eq!(&got[..nwords], &want[..nwords], "f={f}");
        }
    }
}
