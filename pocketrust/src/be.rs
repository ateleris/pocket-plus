use crate::bitstream::{BitReader, BitWriter};

pub fn be(a: &[u64], b: &[u64], w: &mut BitWriter) {
    let mut filler: u64 = 0;
    let mut n: u8 = 0; // bits currently buffered in filler
    for i in (0..b.len()).rev() {
        let cur_a = a[i];
        let mut cur_b = b[i];
        while cur_b != 0 {
            let tz = cur_b.trailing_zeros();
            filler = (filler << 1) | ((cur_a >> tz) & 1);
            n += 1;
            if n == 64 {
                w.add_bits(filler, 64);
                filler = 0;
                n = 0;
            }
            cur_b &= cur_b - 1;
        }
    }
    w.add_bits(filler, n);
}

/// bit extract implmentation that has the reverse read and inverting included
/// needed for y_t calculation (17)
pub fn reverse_be_inverting(a: &[u64], b: &[u64], w: &mut BitWriter) {
    let mut free_slots = 64 - w.idx;
    let mut filler: u64 = 0;

    for i in 0..b.len() {
        if b[i] == 0 {
            continue;
        }
        let cur_num_a = a[i].reverse_bits();
        let mut cur_num_b = b[i].reverse_bits();
        while cur_num_b != 0 {
            let tz = cur_num_b.trailing_zeros();
            filler <<= 1;
            filler += ((cur_num_a >> tz) & 1) ^ 1;
            free_slots -= 1;
            if free_slots == 0 {
                w.add_bits(filler, 64 - w.idx);
                free_slots = 64;
            }
            cur_num_b &= cur_num_b - 1;
        }
    }
    w.add_bits(filler, 64 - w.idx - free_slots);
}

/// inverse of [`be`]
pub(crate) fn try_read_be(
    r: &mut BitReader,
    mask: &[u64],
    prev: &[u64],
    out: &mut [u64],
) -> Option<()> {
    for i in (0..mask.len()).rev() {
        let mut cur_b = mask[i];
        if cur_b == 0 {
            out[i] = prev[i]; // no changed bits here: carry the previous word
            continue;
        }
        let k = cur_b.count_ones() as u8;
        let field = r.try_read(k)?;
        let mut cur_a = prev[i] & !cur_b; // unchanged bits from the previous frame
        let mut shift = k;
        while cur_b != 0 {
            let tz = cur_b.trailing_zeros();
            shift -= 1;
            cur_a |= ((field >> shift) & 1) << tz;
            cur_b &= cur_b - 1;
        }
        out[i] = cur_a;
    }
    Some(())
}

/// inverse of [`reverse_be_inverting`]
pub(crate) fn try_read_reverse_be_inverting(
    r: &mut BitReader,
    b: &[u64],
    out: &mut [u64],
) -> Option<()> {
    for i in 0..b.len() {
        if b[i] == 0 {
            continue;
        }
        let mut rb = b[i].reverse_bits();
        let k = rb.count_ones() as u8;
        let field = r.try_read(k)?;
        let mut ra = 0u64;
        let mut shift = k;
        while rb != 0 {
            let tz = rb.trailing_zeros();
            shift -= 1;
            ra |= (((field >> shift) & 1) ^ 1) << tz;
            rb &= rb - 1;
        }
        out[i] = ra.reverse_bits();
    }
    Some(())
}

#[cfg(test)]
mod tests {
    use super::{be, try_read_be};
    use crate::BUF_LEN;
    use crate::bitstream::{BitReader, BitWriter};

    #[test]
    fn try_read_be_inverts_be_fuzz() {
        // be() (bit-extract) is used by the proven encoder; try_read_be() must invert
        // it for any (values, mask): it re-scatters the b-masked bits of a onto a
        // previous frame `prev`, so got == (prev & !b) | (a & b).
        let mut seed = 0x0fee_1dea_dbee_f001u64;
        let mut rng = || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            seed
        };
        for words in [1usize, 2, 4, 16, 22] {
            for trial in 0..2000 {
                let mut a = [0u64; 64];
                let mut b = [0u64; 64];
                let mut prev = [0u64; 64];
                for w in 0..words {
                    a[w] = rng();
                    prev[w] = rng();
                    b[w] = match trial % 4 {
                        0 => u64::MAX,
                        1 => rng() & rng(),
                        2 => rng() | rng(),
                        _ => rng(),
                    };
                }
                let mut buf = [0u64; 128];
                be(
                    &a[..words],
                    &b[..words],
                    &mut BitWriter::new(&mut buf, 0, 0),
                );
                let mut r = BitReader::new(&buf, 0, 0);
                let mut got = [0u64; BUF_LEN];
                try_read_be(&mut r, &b[..words], &prev[..words], &mut got).unwrap();
                // Merged reconstruction: changed bits from a at b positions, the
                // rest carried from prev.
                for w in 0..words {
                    assert_eq!(
                        got[w],
                        (prev[w] & !b[w]) | (a[w] & b[w]),
                        "words={words} trial={trial} w={w}"
                    );
                }
            }
        }
    }

    #[test]
    fn try_read_be_inverts_be() {
        // Zero `prev` reduces the merge to a plain scatter: (0 & !b) | v == v.
        let a = [0x0123_4567_89AB_CDEFu64, 0xFEDC_BA98_7654_3210];
        let prev = [0u64; 2];
        let masks = [
            [0xFFFF_FFFF_FFFF_FFFFu64, 0x0F0F_0F0F_0F0F_0F0F],
            [0x8000_0000_0000_0001, 0x0000_0000_0000_0000],
            [0u64, 0u64],
        ];
        for b in masks {
            let mut buf = [0u64; 32];
            be(&a, &b, &mut BitWriter::new(&mut buf, 0, 0));
            let mut r = BitReader::new(&buf, 0, 0);
            let mut got = [0u64; BUF_LEN];
            try_read_be(&mut r, &b, &prev, &mut got).unwrap();
            for i in 0..2 {
                assert_eq!(got[i], a[i] & b[i], "b={b:?} word {i}");
            }
        }
    }
}
