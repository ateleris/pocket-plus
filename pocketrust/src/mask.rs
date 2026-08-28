use crate::BUF_LEN;

/// Invert `m_t ^ (m_t << 1)`
pub fn invert_mask_shift(shift: &[u64], f: usize) -> [u64; BUF_LEN] {
    let mut w = [0u64; BUF_LEN];
    if f == 0 {
        return w;
    }
    let nwords = (f + 63) >> 6;
    let mut carry = 0u64;
    for wi in (0..nwords).rev() {
        let x = shift[wi];
        let mut y = x;
        y ^= y << 1;
        y ^= y << 2;
        y ^= y << 4;
        y ^= y << 8;
        y ^= y << 16;
        y ^= y << 32;
        if carry != 0 {
            y = !y; // lower words are full, so flipping all 64 bits is correct
        }
        w[wi] = y;
        carry ^= (x.count_ones() & 1) as u64;
    }
    w
}

#[cfg(test)]
mod tests {
    use super::invert_mask_shift;
    use crate::{BUF_LEN, MAX_PACKET_BITS};

    /// Bit-by-bit reference.
    fn invert_naive(shift: &[u64], f: usize) -> [u64; BUF_LEN] {
        let mut w = [0u64; BUF_LEN];
        let mut prev = 0u64;
        for j in (0..f).rev() {
            let sbit = (shift[j / 64] >> (63 - j % 64)) & 1;
            let wbit = sbit ^ prev;
            if wbit == 1 {
                w[j / 64] |= 1u64 << (63 - j % 64);
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
            let nwords = (f + 63) >> 6;
            let mut shift = [0u64; BUF_LEN];
            for w in shift.iter_mut().take(nwords) {
                *w = rng() & rng();
            }
            let rem = f & 63;
            if rem != 0 {
                shift[nwords - 1] &= !0u64 << (64 - rem);
            }
            let got = invert_mask_shift(&shift, f);
            let want = invert_naive(&shift, f);
            assert_eq!(&got[..nwords], &want[..nwords], "f={f}");
        }
    }
}
