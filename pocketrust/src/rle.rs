use crate::bitstream::{BitReader, BitWriter};
use crate::count::{count, try_read_count};
use crate::{Block, BLOCK_BITS, BLOCK_MASK, BLOCK_MSB, BLOCK_SHIFT};

pub fn reverse_rle(in_vec: &[Block], in_vec_skip: u16, w: &mut BitWriter) {
    let mut c: u32 = 0;
    let last = in_vec.len() - 1;

    let cur_num = in_vec[last] >> in_vec_skip;
    let bits = BLOCK_BITS as u32 - in_vec_skip as u32;
    if cur_num == 0 {
        c += bits;
    } else {
        c = rle_num(cur_num, bits, c, w);
    }

    for &cur_num in in_vec[..last].iter().rev() {
        if cur_num == 0 {
            c += BLOCK_BITS as u32;
            continue;
        }
        c = rle_num(cur_num, BLOCK_BITS as u32, c, w);
    }

    w.add_bits(2, 2);
}

#[inline(always)]
fn rle_num(mut num: Block, bits: u32, c: u32, w: &mut BitWriter) -> u32 {
    let mut prev: i64 = -(c as i64) - 1;
    while num != 0 {
        let p = num.trailing_zeros() as i64;
        count((p - prev) as u16, w);
        prev = p;
        num &= num - 1; // clear lowest set bit (Kernighan)
    }

    bits - 1 - prev as u32
}

/// inverse of [`reverse_rle`]
pub(crate) fn try_read_reverse_rle(
    r: &mut BitReader,
    f: usize,
    out: &mut [Block],
) -> Option<(usize, u32)> {
    let mut q = 0usize;
    let mut ones = 0u32;
    loop {
        let a = try_read_count(r)?;
        if a == 0 {
            break;
        }
        q += a as usize - 1;
        if q < f {
            let p = f - 1 - q;
            out[p >> BLOCK_SHIFT] |= BLOCK_MSB >> (p & BLOCK_MASK);
            ones += 1;
        }
        q += 1;
    }
    Some((q, ones))
}

#[cfg(test)]
mod tests {
    use super::{reverse_rle, try_read_reverse_rle};
    use crate::{Block, BUF_LEN, BLOCK_BITS, BLOCK_MSB};
    use crate::bitstream::{BitReader, BitWriter};

    #[test]
    fn reverse_rle_roundtrips() {
        let cases: [[Block; 2]; 5] = [
            [0, 0],
            [BLOCK_MSB, 0],
            [1, 0],
            [0xDEAD_BEEF_CAFE_F00D_u64 as Block, 0xA5A5_A5A5_A5A5_0000_u64 as Block],
            [Block::MAX, Block::MAX],
        ];
        for c in cases {
            let mut buf: [Block; 64] = [0; 64];
            reverse_rle(&c, 0, &mut BitWriter::new(&mut buf, 0, 0));
            let mut r = BitReader::new(&buf, 0, 0);
            let mut got: [Block; BUF_LEN] = [0; BUF_LEN];
            try_read_reverse_rle(&mut r, 2 * BLOCK_BITS, &mut got).unwrap();
            assert_eq!(&got[..2], &c[..], "case {c:?}");
        }
    }

    #[test]
    fn reverse_rle_roundtrips_fuzz() {
        let mut seed = 0x1234_5678_9abc_def0u64;
        let mut rng = || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            seed as Block
        };
        for words in [1usize, 2, 4, 16, 22, 32] {
            for trial in 0..2000 {
                let mut v: [Block; 64] = [0; 64];
                // Vary density so we get long zero-runs and long one-runs.
                let density = trial % 5;
                for w in 0..words {
                    v[w] = match density {
                        0 => 0,             // long zero runs
                        1 => Block::MAX,      // long one runs
                        2 => rng() & rng(), // sparse ones
                        3 => rng() | rng(), // dense ones
                        _ => rng(),
                    };
                }
                // Occasionally force an isolated high bit far out (long leading run).
                if trial % 7 == 0 && words > 1 {
                    v = [0; 64];
                    v[words - 1] = 1; // single 1 at the very last position
                }
                let nbits = words * BLOCK_BITS;
                let mut buf: [Block; 128] = [0; 128];
                reverse_rle(&v[..words], 0, &mut BitWriter::new(&mut buf, 0, 0));
                let mut r = BitReader::new(&buf, 0, 0);
                let mut got: [Block; BUF_LEN] = [0; BUF_LEN];
                try_read_reverse_rle(&mut r, nbits, &mut got).unwrap();
                assert_eq!(
                    &got[..words],
                    &v[..words],
                    "words={words} trial={trial} density={density}"
                );
            }
        }
    }
}
