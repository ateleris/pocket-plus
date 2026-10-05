use crate::bitstream::{BitReader, BitWriter};
use crate::Block;

#[inline]
pub fn count(a: u16, w: &mut BitWriter) {
    if a == 1 {
        w.add_bits(0, 1);
        return;
    } else if a <= 33 {
        let num_bits = 8;
        let base_val = 0b_1100_0000;
        let val = base_val | (a - 2);

        w.add_bits(val as Block, num_bits);
        return;
    }

    let e = ((2 * ((a - 2).ilog2() + 1)) - 6) as u8;
    let num_bits = 3 + e;
    let base_val: Block = 0b_111 << e;
    let val = base_val | ((a as Block) - 2);

    w.add_bits(val, num_bits);
}

pub fn try_read_count(r: &mut BitReader) -> Option<u32> {
    if r.try_bit()? == 0 {
        return Some(1);
    }
    if r.try_bit()? == 0 {
        return Some(0); // 10 terminator / invalid COUNT
    }
    if r.try_bit()? == 0 {
        return Some(2 + r.try_read(5)? as u32); // 110 + 5-bit payload -> 2..=33
    }

    let mut l = 5u32;
    while r.try_bit()? == 0 {
        l += 1;
        if l >= u32::BITS {
            return None; // COUNT does not fit in a u32
        }
    }
    let low = r.try_read(l as u8)? as u32;
    ((1u32 << l) | low).checked_add(2)
}

#[cfg(test)]
mod tests {
    use super::{count, try_read_count};
    use crate::Block;
    use crate::bitstream::{BitReader, BitWriter};

    #[test]
    fn try_read_count_inverts_count() {
        for a in [1u16, 2, 3, 33, 34, 65, 66, 100, 1000, 65535] {
            let mut buf: [Block; 8] = [0; 8];
            count(a, &mut BitWriter::new(&mut buf, 0, 0));
            let mut r = BitReader::new(&buf, 0, 0);
            assert_eq!(try_read_count(&mut r).unwrap(), a as u32, "a={a}");
        }
    }
}
