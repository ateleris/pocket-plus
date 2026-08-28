
pub struct BitWriter<'a> {
    data: &'a mut [u64],
    pub pos: usize,
    pub idx: u8,
}

impl<'a> BitWriter<'a> {
    pub fn new(data: &'a mut [u64], pos: usize, idx: u8) -> Self {
        Self { data, pos, idx }
    }

    /// Append the low `num_bits` of `val` MSB-first, advancing the cursor.
    #[inline]
    pub fn add_bits(&mut self, val: u64, num_bits: u8) {
        if num_bits == 0 {
            return;
        }

        let mask: u64 = 0xFFFF_FFFF_FFFF_FFFF >> self.idx;
        let out_elem_bits: u8 = u64::BITS as u8;

        let final_idx = self.idx + num_bits;
        if final_idx > out_elem_bits {
            let num_bits_a = out_elem_bits - self.idx;
            let num_bits_b = num_bits - num_bits_a;
            self.data[self.pos] = (self.data[self.pos] & !mask) | ((val >> num_bits_b) & mask);
            self.data[self.pos + 1] =
                self.data[self.pos + 1] | (val << (out_elem_bits - num_bits_b));
            self.pos += 1;
            self.idx = num_bits_b;
            return;
        }
        self.data[self.pos] = (self.data[self.pos] & !mask)
            | ((val << ((out_elem_bits - num_bits) - self.idx)) & mask);

        self.pos += (final_idx >> 6) as usize;
        self.idx = final_idx & 63;
    }
}

pub struct BitReader<'a> {
    data: &'a [u64],
    pub pos: usize,
    pub idx: u8,
    len_bits: usize,
}

impl<'a> BitReader<'a> {
    /// Reader over the whole buffer.
    pub fn new(data: &'a [u64], pos: usize, idx: u8) -> Self {
        Self {
            data,
            pos,
            idx,
            len_bits: data.len() * 64,
        }
    }

    /// Reader bounded to `len_bits` readable bits from the start of `data`.
    pub fn with_len(data: &'a [u64], pos: usize, idx: u8, len_bits: usize) -> Self {
        Self {
            data,
            pos,
            idx,
            len_bits,
        }
    }

    /// Current absolute bit position from the start of `data`.
    #[inline]
    fn bit_pos(&self) -> usize {
        self.pos * 64 + self.idx as usize
    }

    /// Readable bits left before `len_bits` is reached.
    #[inline]
    pub fn remaining(&self) -> usize {
        self.len_bits.saturating_sub(self.bit_pos())
    }

    /// Read `num_bits` (0..=64) MSB-first, returning them in the low bits.
    #[inline]
    fn read(&mut self, num_bits: u8) -> u64 {
        if num_bits == 0 {
            return 0;
        }

        // inside u64
        if self.idx + num_bits <= 64 {
            let shift = 64 - self.idx - num_bits;
            let mask = if num_bits == 64 {
                u64::MAX
            } else {
                (1u64 << num_bits) - 1
            };
            let val = (self.data[self.pos] >> shift) & mask;
            self.idx += num_bits;
            if self.idx == 64 {
                self.pos += 1;
                self.idx = 0;
            }
            return val;
        }

        // across u64
        let mut val = 0u64;
        let mut remaining = num_bits;
        while remaining > 0 {
            let avail = 64 - self.idx;
            let take = remaining.min(avail);
            let shift = avail - take;
            let mask = if take == 64 {
                u64::MAX
            } else {
                (1u64 << take) - 1
            };
            let chunk = (self.data[self.pos] >> shift) & mask;
            val = if take == 64 {
                chunk
            } else {
                (val << take) | chunk
            };
            self.idx += take;
            remaining -= take;
            if self.idx == 64 {
                self.pos += 1;
                self.idx = 0;
            }
        }
        val
    }

    #[inline]
    pub fn try_read(&mut self, num_bits: u8) -> Option<u64> {
        if (num_bits as usize) > self.remaining() {
            return None;
        }
        Some(self.read(num_bits))
    }

    #[inline]
    pub fn try_bit(&mut self) -> Option<u64> {
        self.try_read(1)
    }
}

#[cfg(test)]
mod tests {
    use super::{BitReader, BitWriter};

    #[test]
    fn read_inverts_add_bits() {
        let mut buf = [0u64; 8];
        let mut w = BitWriter::new(&mut buf, 0, 0);
        w.add_bits(0b1011, 4);
        w.add_bits(0x1234_5678_9ABC_DEF0, 64);
        let mut r = BitReader::new(&buf, 0, 0);
        assert_eq!(r.try_read(4).unwrap(), 0b1011);
        assert_eq!(r.try_read(64).unwrap(), 0x1234_5678_9ABC_DEF0);
    }
}
