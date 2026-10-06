
use crate::{Block, BLOCK_BITS, BLOCK_MASK, BLOCK_SHIFT};

const BITS: u8 = BLOCK_BITS as u8;

pub struct BitWriter<'a> {
    data: &'a mut [Block],
    pub pos: usize,
    pub idx: u8,
}

impl<'a> BitWriter<'a> {
    pub fn new(data: &'a mut [Block], pos: usize, idx: u8) -> Self {
        Self { data, pos, idx }
    }

    #[cfg(feature = "trace")]
    #[inline]
    pub fn bit_pos(&self) -> usize {
        self.pos * BLOCK_BITS + self.idx as usize
    }

    /// Append the low `num_bits` of `val` MSB-first, advancing the cursor.
    #[inline]
    pub fn add_bits(&mut self, val: Block, num_bits: u8) {
        if num_bits == 0 {
            return;
        }

        let mask = Block::MAX >> self.idx;

        let final_idx = self.idx + num_bits;
        if final_idx > BITS {
            let num_bits_a = BITS - self.idx;
            let num_bits_b = num_bits - num_bits_a;
            self.data[self.pos] = (self.data[self.pos] & !mask) | ((val >> num_bits_b) & mask);
            self.data[self.pos + 1] =
                self.data[self.pos + 1] | (val << (BITS - num_bits_b));
            self.pos += 1;
            self.idx = num_bits_b;
            return;
        }
        self.data[self.pos] = (self.data[self.pos] & !mask)
            | ((val << ((BITS - num_bits) - self.idx)) & mask);

        self.pos += (final_idx >> BLOCK_SHIFT) as usize;
        self.idx = final_idx & BLOCK_MASK as u8;
    }
}

pub struct BitReader<'a> {
    data: &'a [Block],
    pub pos: usize,
    pub idx: u8,
    len_bits: usize,
}

impl<'a> BitReader<'a> {
    /// Reader over the whole buffer.
    pub fn new(data: &'a [Block], pos: usize, idx: u8) -> Self {
        Self {
            data,
            pos,
            idx,
            len_bits: data.len() * BLOCK_BITS,
        }
    }

    /// Reader bounded to `len_bits` readable bits from the start of `data`.
    pub fn with_len(data: &'a [Block], pos: usize, idx: u8, len_bits: usize) -> Self {
        Self {
            data,
            pos,
            idx,
            len_bits,
        }
    }

    /// Current absolute bit position from the start of `data`.
    #[inline]
    pub(crate) fn bit_pos(&self) -> usize {
        self.pos * BLOCK_BITS + self.idx as usize
    }

    /// Readable bits left before `len_bits` is reached.
    #[inline]
    pub fn remaining(&self) -> usize {
        self.len_bits.saturating_sub(self.bit_pos())
    }

    /// Read `num_bits` (0..=BLOCK_BITS) MSB-first, returning them in the low bits.
    #[inline]
    fn read(&mut self, num_bits: u8) -> Block {
        if num_bits == 0 {
            return 0;
        }

        // inside one block
        if self.idx + num_bits <= BITS {
            let shift = BITS - self.idx - num_bits;
            let mask = if num_bits == BITS {
                Block::MAX
            } else {
                (1 << num_bits) - 1
            };
            let val = (self.data[self.pos] >> shift) & mask;
            self.idx += num_bits;
            if self.idx == BITS {
                self.pos += 1;
                self.idx = 0;
            }
            return val;
        }

        // across blocks
        let mut val: Block = 0;
        let mut remaining = num_bits;
        while remaining > 0 {
            let avail = BITS - self.idx;
            let take = remaining.min(avail);
            let shift = avail - take;
            let mask = if take == BITS {
                Block::MAX
            } else {
                (1 << take) - 1
            };
            let chunk = (self.data[self.pos] >> shift) & mask;
            val = if take == BITS {
                chunk
            } else {
                (val << take) | chunk
            };
            self.idx += take;
            remaining -= take;
            if self.idx == BITS {
                self.pos += 1;
                self.idx = 0;
            }
        }
        val
    }

    #[inline]
    pub fn try_read(&mut self, num_bits: u8) -> Option<Block> {
        if (num_bits as usize) > self.remaining() {
            return None;
        }
        Some(self.read(num_bits))
    }

    #[inline]
    pub fn try_bit(&mut self) -> Option<Block> {
        self.try_read(1)
    }
}

#[cfg(test)]
mod tests {
    use super::{BitReader, BitWriter};
    use crate::{Block, BLOCK_BITS};

    #[test]
    fn read_inverts_add_bits() {
        let mut buf: [Block; 8] = [0; 8];
        let mut w = BitWriter::new(&mut buf, 0, 0);
        w.add_bits(0b1011, 4);
        let v = 0x1234_5678_9ABC_DEF0_u64 as Block;
        w.add_bits(v, BLOCK_BITS as u8);
        let mut r = BitReader::new(&buf, 0, 0);
        assert_eq!(r.try_read(4).unwrap(), 0b1011);
        assert_eq!(r.try_read(BLOCK_BITS as u8).unwrap(), v);
    }
}
