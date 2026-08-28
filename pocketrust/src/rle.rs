use crate::bitstream::{BitReader, BitWriter};
use crate::count::{count, try_read_count};

pub fn reverse_rle(in_vec: &[u64], in_vec_skip: u16, w: &mut BitWriter) {
    let mut c: u32 = 0;
    let last = in_vec.len() - 1;

    let cur_num = in_vec[last] >> in_vec_skip;
    let bits = 64 - in_vec_skip as u32;
    if cur_num == 0 {
        c += bits;
    } else {
        c = rle_num(cur_num, bits, c, w);
    }

    for &cur_num in in_vec[..last].iter().rev() {
        if cur_num == 0 {
            c += 64;
            continue;
        }
        c = rle_num(cur_num, 64, c, w);
    }

    w.add_bits(2, 2);
}

#[inline(always)]
fn rle_num(mut num: u64, bits: u32, c: u32, w: &mut BitWriter) -> u32 {
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
    out: &mut [u64],
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
            out[p >> 6] |= 1u64 << (63 - (p & 63));
            ones += 1;
        }
        q += 1;
    }
    Some((q, ones))
}

#[cfg(test)]
mod tests {
    use super::{reverse_rle, try_read_reverse_rle};
    use crate::BUF_LEN;
    use crate::bitstream::{BitReader, BitWriter};

    #[test]
    fn reverse_rle_roundtrips() {
        let cases = [
            [0u64, 0],
            [0x8000_0000_0000_0000, 0],
            [0x0000_0000_0000_0001, 0],
            [0xDEAD_BEEF_CAFE_F00D, 0xA5A5_A5A5_0000_0000],
            [0xFFFF_FFFF_FFFF_FFFF, 0xFFFF_FFFF_FFFF_FFFF],
        ];
        for c in cases {
            let mut buf = [0u64; 64];
            reverse_rle(&c, 0, &mut BitWriter::new(&mut buf, 0, 0));
            let mut r = BitReader::new(&buf, 0, 0);
            let mut got = [0u64; BUF_LEN];
            try_read_reverse_rle(&mut r, 128, &mut got).unwrap();
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
            seed
        };
        for words in [1usize, 2, 4, 16, 22, 32] {
            for trial in 0..2000 {
                let mut v = [0u64; 64];
                // Vary density so we get long zero-runs and long one-runs.
                let density = trial % 5;
                for w in 0..words {
                    v[w] = match density {
                        0 => 0,             // long zero runs
                        1 => u64::MAX,      // long one runs
                        2 => rng() & rng(), // sparse ones
                        3 => rng() | rng(), // dense ones
                        _ => rng(),
                    };
                }
                // Occasionally force an isolated high bit far out (long leading run).
                if trial % 7 == 0 && words > 1 {
                    v = [0u64; 64];
                    v[words - 1] = 1; // single 1 at the very last position
                }
                let nbits = words * 64;
                let mut buf = [0u64; 128];
                reverse_rle(&v[..words], 0, &mut BitWriter::new(&mut buf, 0, 0));
                let mut r = BitReader::new(&buf, 0, 0);
                let mut got = [0u64; BUF_LEN];
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
