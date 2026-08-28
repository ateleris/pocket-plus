use crate::bitstream::{BitReader, BitWriter};

#[inline]
pub fn count(a: u16, w: &mut BitWriter) {
    if a == 1 {
        w.add_bits(0, 1);
        return;
    } else if a <= 33 {
        let num_bits = 8;
        let base_val = 0b_1100_0000;
        let val = base_val | (a - 2);

        w.add_bits(val as u64, num_bits);
        return;
    }

    let e = ((2 * ((a - 2).ilog2() + 1)) - 6) as u8;
    let num_bits = 3 + e;
    let base_val: u64 = 0b_111 << e;
    let val = base_val | ((a as u64) - 2);

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

    let mut z = 0u32;
    while r.try_bit()? == 0 {
        z += 1;
        if z > 64 {
            return None; // wider than any representable COUNT
        }
    }
    let l = z + 5;
    if l >= 64 {
        return None; // 1u64 << l would overflow
    }
    let low = r.try_read(l as u8)?;
    Some((((1u64 << l) | low) + 2) as u32)
}

#[cfg(test)]
mod tests {
    use super::{count, try_read_count};
    use crate::bitstream::{BitReader, BitWriter};

    #[test]
    fn try_read_count_inverts_count() {
        for a in [1u16, 2, 3, 33, 34, 65, 66, 100, 1000, 65535] {
            let mut buf = [0u64; 8];
            count(a, &mut BitWriter::new(&mut buf, 0, 0));
            let mut r = BitReader::new(&buf, 0, 0);
            assert_eq!(try_read_count(&mut r).unwrap(), a as u32, "a={a}");
        }
    }
}
