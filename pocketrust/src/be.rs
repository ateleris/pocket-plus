use crate::bitstream::{BitReader, BitWriter};
use crate::{Block, BLOCK_BITS};

pub fn be(a: &[Block], b: &[Block], w: &mut BitWriter) {
    let mut filler: Block = 0;
    let mut n: u8 = 0; // bits currently buffered in filler
    for i in (0..b.len()).rev() {
        let cur_a = a[i];
        let mut cur_b = b[i];
        while cur_b != 0 {
            let tz = cur_b.trailing_zeros();
            filler = (filler << 1) | ((cur_a >> tz) & 1);
            n += 1;
            if n == BLOCK_BITS as u8 {
                w.add_bits(filler, n);
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
pub fn reverse_be_inverting(a: &[Block], b: &[Block], w: &mut BitWriter) {
    let mut free_slots = BLOCK_BITS as u8 - w.idx;
    let mut filler: Block = 0;

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
                w.add_bits(filler, BLOCK_BITS as u8 - w.idx);
                free_slots = BLOCK_BITS as u8;
            }
            cur_num_b &= cur_num_b - 1;
        }
    }
    w.add_bits(filler, BLOCK_BITS as u8 - w.idx - free_slots);
}

/// inverse of [`be`]
pub(crate) fn try_read_be(
    r: &mut BitReader,
    mask: &[Block],
    prev: &[Block],
    out: &mut [Block],
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
    b: &[Block],
    out: &mut [Block],
) -> Option<()> {
    for i in 0..b.len() {
        if b[i] == 0 {
            continue;
        }
        let mut rb = b[i].reverse_bits();
        let k = rb.count_ones() as u8;
        let field = r.try_read(k)?;
        let mut ra: Block = 0;
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
    use crate::{Block, BUF_LEN, BLOCK_MSB};
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
            seed as Block
        };
        for words in [1usize, 2, 4, 16, 22] {
            for trial in 0..2000 {
                let mut a: [Block; 64] = [0; 64];
                let mut b: [Block; 64] = [0; 64];
                let mut prev: [Block; 64] = [0; 64];
                for w in 0..words {
                    a[w] = rng();
                    prev[w] = rng();
                    b[w] = match trial % 4 {
                        0 => Block::MAX,
                        1 => rng() & rng(),
                        2 => rng() | rng(),
                        _ => rng(),
                    };
                }
                let mut buf: [Block; 128] = [0; 128];
                be(
                    &a[..words],
                    &b[..words],
                    &mut BitWriter::new(&mut buf, 0, 0),
                );
                let mut r = BitReader::new(&buf, 0, 0);
                let mut got: [Block; BUF_LEN] = [0; BUF_LEN];
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
        let a = [0x0123_4567_89AB_CDEF_u64 as Block, 0xFEDC_BA98_7654_3210_u64 as Block];
        let prev: [Block; 2] = [0; 2];
        let masks: [[Block; 2]; 3] = [
            [Block::MAX, 0x0F0F_0F0F_0F0F_0F0F_u64 as Block],
            [BLOCK_MSB | 1, 0],
            [0, 0],
        ];
        for b in masks {
            let mut buf: [Block; 32] = [0; 32];
            be(&a, &b, &mut BitWriter::new(&mut buf, 0, 0));
            let mut r = BitReader::new(&buf, 0, 0);
            let mut got: [Block; BUF_LEN] = [0; BUF_LEN];
            try_read_be(&mut r, &b, &prev, &mut got).unwrap();
            for i in 0..2 {
                assert_eq!(got[i], a[i] & b[i], "b={b:?} word {i}");
            }
        }
    }
}
